package zju.cst.aces.runner;

import org.junit.platform.launcher.listeners.TestExecutionSummary;
import zju.cst.aces.api.Phase;
import zju.cst.aces.api.config.Config;
import zju.cst.aces.api.impl.ChatGenerator;
import zju.cst.aces.api.impl.PromptConstructorImpl;
import zju.cst.aces.api.impl.RepairImpl;
import zju.cst.aces.api.impl.obfuscator.Obfuscator;
import zju.cst.aces.dto.*;
import zju.cst.aces.util.CodeExtractor;
import zju.cst.aces.util.TestProcessor;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

public class MethodRunner extends ClassRunner {

    public MethodInfo methodInfo;

    public MethodRunner(Config config, String fullClassName, MethodInfo methodInfo) throws IOException {
        super(config, fullClassName);
        this.methodInfo = methodInfo;
    }

    @Override
    public void start() throws IOException {
        if (!config.isStopWhenSuccess() && config.isEnableMultithreading()) {
            ExecutorService executor = Executors.newFixedThreadPool(config.getTestNumber());
            List<Future<String>> futures = new ArrayList<>();
            for (int num = 0; num < config.getTestNumber(); num++) {
                int finalNum = num;
                Callable<String> callable = new Callable<String>() {
                    @Override
                    public String call() throws Exception {
                        startRounds(finalNum);
                        return "";
                    }
                };
                Future<String> future = executor.submit(callable);
                futures.add(future);
            }
            Runtime.getRuntime().addShutdownHook(new Thread() {
                public void run() {
                    executor.shutdownNow();
                }
            });

            for (Future<String> future : futures) {
                try {
                    String result = future.get();
                    System.out.println(result);
                } catch (InterruptedException | ExecutionException e) {
                    e.printStackTrace();
                }
            }

            executor.shutdown();
        } else {
            for (int num = 0; num < config.getTestNumber(); num++) {
                if (startRounds(num) && config.isStopWhenSuccess()) {
                    break;
                }
            }
        }
    }

    public boolean startRounds(final int num) throws IOException {

        Phase phase = new Phase(config);
        PromptConstructorImpl pc = phase.new PromptGeneration(classInfo, methodInfo).execute(num);

        PromptInfo promptInfo = pc.getPromptInfo();
        promptInfo.setRound(config.getMaxRounds());
        for (int rounds = 0; rounds < config.getMaxRounds(); rounds++) {

            phase.new TestGeneration().execute(pc);

            if (phase.new Validation().execute(pc)) {
                exportRecord(pc.getPromptInfo(), classInfo, num);
                return true;
            }
        }
        exportRecord(pc.getPromptInfo(), classInfo, num);
        return false;
    }

    public String generateTest(List<ChatMessage> prompt, RoundRecord record) throws IOException {

        if (isExceedMaxTokens(config.getMaxPromptTokens(), prompt)) {
            config.getLogger().error("Exceed max prompt tokens: " + methodInfo.methodName + " Skipped.");
            record.setPromptToken(-1);
            record.setHasCode(false);
            return "";
        }
        config.getLogger().debug("[Prompt]:\n" + prompt.toString());

        ChatResponse response = ChatGenerator.chat(config, prompt);
        String content = ChatGenerator.getContentByResponse(response);
        config.getLogger().debug("[Response]:\n" + content);
        String code = ChatGenerator.extractCodeByContent(content);

        record.setPromptToken(response.getUsage().getPromptTokens());
        record.setResponseToken(response.getUsage().getCompletionTokens());
        record.setPrompt(prompt);
        record.setResponse(content);
        if (code.isEmpty()) {
            config.getLogger().info("Test for method < " + methodInfo.methodName + " > extract code failed");
            record.setHasCode(false);
            return "";
        }
        record.setHasCode(true);
        return code;
    }

    public String generateTest(List<ChatMessage> prompt) throws IOException {

        if (isExceedMaxTokens(config.getMaxPromptTokens(), prompt)) {
            config.getLogger().error("Exceed max prompt tokens: " + methodInfo.methodName + " Skipped.");
            return "";
        }
        config.getLogger().debug("[Prompt]:\n" + prompt.toString());

        ChatResponse response = ChatGenerator.chat(config, prompt);
        String content = ChatGenerator.getContentByResponse(response);
        String code = ChatGenerator.extractCodeByContent(content);

        if (code.isEmpty()) {
            config.getLogger().info("Test for method < " + methodInfo.methodName + " > extract code failed");
            return "";
        }
        return code;
    }

    public static boolean runTest(Config config, String fullTestName, PromptInfo promptInfo, int rounds) {
        String testName = fullTestName.substring(fullTestName.lastIndexOf(".") + 1);
        Path savePath = config.getTestOutput().resolve(fullTestName.replace(".", File.separator) + ".java");
        if (promptInfo.getTestPath() == null) {
            promptInfo.setTestPath(savePath);
        }

        TestProcessor testProcessor = new TestProcessor(fullTestName);
        String code = promptInfo.getUnitTest();
        if (rounds >= 1) {
            code = testProcessor.addCorrectTest(promptInfo);
        }

        // Compilation
        Path compilationErrorPath = config.getErrorOutput().resolve(testName + "_CompilationError_" + rounds + ".txt");
        Path executionErrorPath = config.getErrorOutput().resolve(testName + "_ExecutionError_" + rounds + ".txt");
        boolean compileResult = config.getValidator().semanticValidate(code, testName, compilationErrorPath, promptInfo);
        if (!compileResult) {
            config.getLogger().info("Test for method < " + promptInfo.getMethodInfo().getMethodName() + " > compilation failed round " + rounds);
            return false;
        }
        if (config.isNoExecution()) {
            exportTest(code, savePath);
            config.getLogger().info("Test for method < " + promptInfo.getMethodInfo().getMethodName() + " > generated successfully round " + rounds);
            return true;
        }

        // Execution
        TestExecutionSummary summary = config.getValidator().execute(fullTestName);
        if (summary.getTestsFailedCount() > 0 || summary.getTestsSucceededCount() == 0) {
            String testProcessed = testProcessor.removeErrorTest(promptInfo, summary);

            // Remove errors successfully, recompile and re-execute test
            if (testProcessed != null) {
                config.getLogger().debug("[Original Test]:\n" + code);
                if (config.getValidator().semanticValidate(testProcessed, testName, compilationErrorPath, null)) {
                    if (config.getValidator().runtimeValidate(fullTestName)) {
                        exportTest(testProcessed, savePath);
                        config.getLogger().debug("[Processed Test]:\n" + testProcessed);
                        config.getLogger().info("Processed test for method < " + promptInfo.getMethodInfo().getMethodName() + " > generated successfully round " + rounds);
                        return true;
                    }
                }
                testProcessor.removeCorrectTest(promptInfo, summary);
            }

            // Set promptInfo error message
            TestMessage testMessage = new TestMessage();
            List<String> errors = new ArrayList<>();
            summary.getFailures().forEach(failure -> {
                for (StackTraceElement st : failure.getException().getStackTrace()) {
                    if (st.getClassName().contains(fullTestName)) {
                        errors.add("Error in " + failure.getTestIdentifier().getLegacyReportingName()
                                + ": line " + st.getLineNumber() + " : "
                                + failure.getException().toString());
                    }
                }
            });
            testMessage.setErrorType(TestMessage.ErrorType.RUNTIME_ERROR);
            testMessage.setErrorMessage(errors);
            promptInfo.setErrorMsg(testMessage);
            exportError(code, errors, executionErrorPath);
            testProcessor.removeCorrectTest(promptInfo, summary);
            config.getLogger().info("Test for method < " + promptInfo.getMethodInfo().getMethodName() + " > execution failed round " + rounds);
            return false;
        }
//            summary.printTo(new PrintWriter(System.out));
        exportTest(code, savePath);
        config.getLogger().info("Test for method < " + promptInfo.getMethodInfo().getMethodName() + " > compile and execute successfully round " + rounds);
        return true;
    }


    public static void exportError(String code, List<String> errors, Path outputPath) {
        try {
            BufferedWriter writer = new BufferedWriter(new FileWriter(outputPath.toFile()));
            writer.write(code);
            writer.write("\n--------------------------------------------\n");
            writer.write(String.join("\n", errors));
            writer.close();
        } catch (Exception e) {
            throw new RuntimeException("In TestCompiler.exportError: " + e);
        }
    }
}