package com.inkslab.inkOJbackend.judge;

import cn.hutool.json.JSONUtil;
import com.inkslab.inkOJbackend.common.ErrorCode;
import com.inkslab.inkOJbackend.exception.BusinessException;
import com.inkslab.inkOJbackend.judge.codeSandbox.CodeSandbox;
import com.inkslab.inkOJbackend.judge.codeSandbox.CodeSandboxFactory;
import com.inkslab.inkOJbackend.judge.codeSandbox.CodeSandboxProxy;
import com.inkslab.inkOJbackend.judge.model.ExecuteCodeRequest;
import com.inkslab.inkOJbackend.judge.model.ExecuteCodeResponse;
import com.inkslab.inkOJbackend.judge.model.JudgeContext;
import com.inkslab.inkOJbackend.judge.strategy.JudgeStrategyManager;
import com.inkslab.inkOJbackend.model.dto.question.JudgeCase;
import com.inkslab.inkOJbackend.judge.model.JudgeInfo;
import com.inkslab.inkOJbackend.model.entity.Question;
import com.inkslab.inkOJbackend.model.entity.QuestionSubmit;
import com.inkslab.inkOJbackend.model.enums.QuestionSubmitEnum;
import com.inkslab.inkOJbackend.model.enums.QuestionSubmitStatusEnum;
import com.inkslab.inkOJbackend.service.QuestionService;
import com.inkslab.inkOJbackend.service.QuestionSubmitService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class JudgeServiceImpl implements JudgeService {

    @Resource
    private QuestionService questionService;

    @Resource
    private QuestionSubmitService questionSubmitService;

    @Resource
    private JudgeStrategyManager strategyManager;

    //沙箱的类型，对应与配置文件中的配置
    @Value("${codesandbox.type}")
    private String type;

    /**
     * 判题操作
     * @param questionSubmitId 提交题目的id
     * @return
     */
    @Override
    public QuestionSubmit doJudge(long questionSubmitId) {
        //1.根据题目id获取题目信息，以及提交的信息
        QuestionSubmit submit = questionSubmitService.getById(questionSubmitId);
        if(submit == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR,"未找到题目提交信息");
        }
        Long questionId = submit.getQuestionId();
        Question question = questionService.getById(questionId);
        if(question == null){
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR,"题目不存在");
        }

        String language = submit.getLanguage();
        String code = submit.getCode();
        Integer status = submit.getStatus();

        if(!status.equals(QuestionSubmitEnum.WAITING.getValue())){
            throw new BusinessException(ErrorCode.FORBIDDEN_ERROR,"已经判题");
        }

        //得到题目的测试的输入用例,输出用例
        String judgeCase = question.getJudgeCase();
        List<JudgeCase> judgeCases = JSONUtil.toList(judgeCase, JudgeCase.class);
        List<String> inputList = judgeCases.stream().map(JudgeCase::getInput).collect(Collectors.toList());
        List<String> outputList= judgeCases.stream().map(JudgeCase::getOutput).collect(Collectors.toList());

        //1.1更改题目的状态
        //把状态更改为判题中
        QuestionSubmit questionSubmitUpdate = new QuestionSubmit();
        questionSubmitUpdate.setId(questionSubmitId);
        questionSubmitUpdate.setStatus(QuestionSubmitStatusEnum.RUNNING.getValue());
        boolean update = questionSubmitService.updateById(questionSubmitUpdate);
        if(!update){
            throw new BusinessException(ErrorCode.SYSTEM_ERROR,"题目状态更新失败!");
        }
        //2.调用沙箱的代理对象，获取执行结果
        CodeSandbox codeSandbox = CodeSandboxFactory.newInstance(type);
        codeSandbox = new CodeSandboxProxy(codeSandbox);
        ExecuteCodeRequest executeCodeResponse = ExecuteCodeRequest.builder()
                .input(inputList)
                .language(language)
                .code(code)
                .build();
        ExecuteCodeResponse response = codeSandbox.executeCode(executeCodeResponse);

        //状态已经错误了，直接不用判题了，直接返回就行了
        JudgeInfo judgeInfo = new JudgeInfo();
        questionSubmitUpdate = new QuestionSubmit();
        if(response.getStatus() == 3){
            judgeInfo.setMessage(response.getMessage());
            judgeInfo.setTime(null);
            judgeInfo.setMemory(null);
            questionSubmitUpdate.setStatus(QuestionSubmitStatusEnum.FAILED.getValue());
        }else{
            //获取判题上下文的信息，并传递给判题策略管理员，进行判题操作
            JudgeContext judgeContext = new JudgeContext();
            judgeContext.setJudgeInfo(response.getJudgeInfo());
            judgeContext.setInputList(inputList);
            judgeContext.setOutputList(response.getOutputList());
            judgeContext.setQuestion(question);
            judgeContext.setJudgeCases(judgeCases);
            judgeContext.setQuestionSubmit(submit);
            judgeContext.setMessage(response.getMessage());
            strategyManager = new JudgeStrategyManager();
            judgeInfo = strategyManager.doJudge(judgeContext);
            questionSubmitUpdate.setStatus(QuestionSubmitStatusEnum.SUCCEED.getValue());
        }
        //修改数据库中判题结果
        questionSubmitUpdate.setId(questionSubmitId);
        questionSubmitUpdate.setJudgeInfo(JSONUtil.toJsonStr(judgeInfo));
        boolean update1 = questionSubmitService.updateById(questionSubmitUpdate);
        if(!update1){
            throw new BusinessException(ErrorCode.SYSTEM_ERROR,"更新判题结果失败！");
        }
        QuestionSubmit questionSubmit = questionSubmitService.getById(questionSubmitId);
        return questionSubmit;
    }
}
