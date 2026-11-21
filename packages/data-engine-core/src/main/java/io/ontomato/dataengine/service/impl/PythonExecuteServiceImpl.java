package io.ontomato.dataengine.service.impl;

import java.io.File;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.alibaba.fastjson2.JSONObject;

import io.ontomato.dataengine.bean.PythonExecuteResult;
import io.ontomato.dataengine.config.DataRagConfig;
import io.ontomato.dataengine.service.BackendSessionEntrance;
import io.ontomato.dataengine.service.LangService;
import io.ontomato.dataengine.service.PythonExecuteService;
import io.ontomato.dataengine.service.SessionCancelledException;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class PythonExecuteServiceImpl implements PythonExecuteService {

    File pythonPath = new File("./python");

    @Autowired
    private DataRagConfig dataRagConfig;

    @Autowired
    private LangService langService;

    /** The port programs call /dsl/execute on; only that local origin's answers are recorded. */
    @Value("${server.port}")
    private int serverPort;

    @Override
    public PythonExecuteResult runPythonCode(String sessionId, String code, int timeoutMinute, String lang, String... args) throws Exception {
        Files.createDirectories(pythonPath.toPath());
        File file = new File(pythonPath, sessionId + ".py");
        Files.writeString(file.toPath(), code);
        // Saved programs, cards and dashboards continue to use the original service working directory.
        return executeFile(sessionId, file, null, timeoutMinute, lang, args);
    }

    @Override
    public PythonExecuteResult runPythonFile(String sessionId, File file, int timeoutMinute, String lang, String... args) throws Exception {
        return executeFile(sessionId, file, file.getAbsoluteFile().getParentFile(), timeoutMinute, lang, args);
    }

    /**
     * Sliced wait duration for the process: the upper bound of the cancellation-awareness delay, with negligible overhead.
     */
    private static final long WAIT_SLICE_MS = 200L;

    /** Launcher that runs the program and records partial /dsl/execute answers into the run's status file. */
    private static final String QUERY_RUNTIME_RESOURCE = "/python/query_runtime.py";
    private static final String QUERY_STATUS_ENV = "ONTOMATO_QUERY_STATUS_FILE";
    private static final String ENGINE_PORT_ENV = "ONTOMATO_ENGINE_PORT";

    private PythonExecuteResult executeFile(String sessionId, File file, File directory, int timeoutMinute, String lang, String... args) throws Exception {
        long start = System.currentTimeMillis();
        Process process = null;
        Path stdout = null;
        Path stderr = null;
        Path runtime = null;
        Path queryStatus = null;
        try {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Python execution has been cancelled");
            log.info("[{}] pythonCode:\n{}", sessionId, Files.readString(file.toPath()));
            String executable = dataRagConfig.getPythonpath();
            if (executable == null || executable.isEmpty()) executable = "python";
            if (executable.contains(File.separator)) executable = Path.of(executable).toAbsolutePath().toString();
            runtime = Files.createTempFile("datarag-python-runtime-", ".py");
            try (InputStream source = PythonExecuteServiceImpl.class.getResourceAsStream(QUERY_RUNTIME_RESOURCE)) {
                Files.write(runtime, source.readAllBytes());
            }
            // Created per run and read only after this run exits, so no execution inherits another's status.
            queryStatus = Files.createTempFile("datarag-python-", ".query");
            List<String> command = new ArrayList<>(List.of(executable, runtime.toString(), file.getAbsolutePath()));
            if (args != null) command.addAll(Arrays.asList(args));
            log.info("python command: {}", command);
            stdout = Files.createTempFile("datarag-python-", ".out");
            stderr = Files.createTempFile("datarag-python-", ".err");
            ProcessBuilder builder = new ProcessBuilder(command).directory(directory)
                    .redirectOutput(stdout.toFile()).redirectError(stderr.toFile());
            builder.environment().put(QUERY_STATUS_ENV, queryStatus.toString());
            builder.environment().put(ENGINE_PORT_ENV, String.valueOf(serverPort));
            process = builder.start();
            // Sliced waiting: on detecting cancellation, throw immediately and go through the existing process-tree kill logic in finally; timeout semantics are the same as before.
            long deadlineNanos = System.nanoTime() + TimeUnit.MINUTES.toNanos(timeoutMinute);
            while (System.nanoTime() < deadlineNanos && !process.waitFor(WAIT_SLICE_MS, TimeUnit.MILLISECONDS)) {
                if (BackendSessionEntrance.isCancelled(sessionId)) {
                    throw new SessionCancelledException(sessionId);
                }
            }
            if (process.isAlive()) {
                throw new Exception("[" + sessionId + "] " + langService.get(lang, "PythonExecutor.execute.timeout") + ": " + timeoutMinute);
            }
            String charsetName = dataRagConfig.getPythonCharset();
            Charset charset = Charset.forName(charsetName == null || charsetName.isEmpty() ? "utf-8" : charsetName);
            String output = Files.readString(stdout, charset);
            String error = Files.readString(stderr, charset);
            log.info("[{}] pythonOutput:{}", sessionId, output);
            if (!error.isEmpty()) log.info("[{}] pythonError:{}", sessionId, error);
            if (process.exitValue() != 0 && error.isEmpty()) error = "Python process exit code was " + process.exitValue();
            PythonExecuteResult result = new PythonExecuteResult();
            result.setResult(output.isEmpty() ? null : output);
            result.setError(error.isEmpty() ? null : error);
            result.setSpendTime(System.currentTimeMillis() - start);
            if (Files.size(queryStatus) > 0) {
                result.setPartialQuery(JSONObject.parseObject(Files.readString(queryStatus, StandardCharsets.UTF_8)));
            }
            return result;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw e;
        } finally {
            boolean interrupted = Thread.interrupted();
            try {
                if (process != null && process.isAlive()) {
                    process.descendants().forEach(ProcessHandle::destroyForcibly);
                    process.destroyForcibly();
                    process.waitFor(5, TimeUnit.SECONDS);
                }
            } catch (InterruptedException e) {
                interrupted = true;
            } finally {
                for (Path output : new Path[] {stdout, stderr, runtime, queryStatus}) {
                    if (output != null) {
                        try { Files.deleteIfExists(output); }
                        catch (Exception e) { log.warn("[{}] Failed to clean up the Python output temporary file", sessionId, e); }
                    }
                }
                if (interrupted) Thread.currentThread().interrupt();
            }
        }
    }
}
