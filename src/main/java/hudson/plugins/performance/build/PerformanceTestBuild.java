package hudson.plugins.performance.build;

import com.google.common.base.Throwables;
import hudson.EnvVars;
import hudson.Extension;
import hudson.FilePath;
import hudson.Functions;
import hudson.Launcher;
import hudson.model.AbstractProject;
import hudson.model.Result;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.plugins.performance.Messages;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.Builder;
import jenkins.tasks.SimpleBuildStep;
import org.apache.commons.io.output.NullOutputStream;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;

import javax.annotation.Nonnull;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * "Build step" for running performance test
 */
public class PerformanceTestBuild extends Builder implements SimpleBuildStep {

    protected final static String PERFORMANCE_TEST_COMMAND = "bzt";
    protected final static String VIRTUALENV_COMMAND = "virtualenv";
    protected final static String HELP_COMMAND = "--help";
    protected final static String VIRTUALENV_PATH_UNIX = "taurus-venv/bin/";
    protected final static String VIRTUALENV_PATH_WINDOWS = "taurus-venv\\Scripts";
    protected final static String VIRTUALENV_PATH = Functions.isWindows() ? VIRTUALENV_PATH_WINDOWS : VIRTUALENV_PATH_UNIX;
    protected final static String[] CHECK_BZT_COMMAND = new String[]{PERFORMANCE_TEST_COMMAND, HELP_COMMAND};
    protected final static String[] CHECK_VIRTUALENV_BZT_COMMAND = new String[]{VIRTUALENV_PATH + PERFORMANCE_TEST_COMMAND, HELP_COMMAND};
    protected final static String[] CHECK_VIRTUALENV_COMMAND = new String[]{VIRTUALENV_COMMAND, HELP_COMMAND};
    protected final static String[] CREATE_LOCAL_PYTHON_COMMAND_WITH_SYSTEM_PACKAGES_OPTION =
            new String[]{VIRTUALENV_COMMAND, "--clear", "--system-site-packages", "taurus-venv"};
    protected final static String[] CREATE_LOCAL_PYTHON_COMMAND = new String[]{VIRTUALENV_COMMAND, "--clear", "taurus-venv"};
    protected final static String[] INSTALL_BZT_COMMAND = new String[]{VIRTUALENV_PATH + "pip", "install", PERFORMANCE_TEST_COMMAND};
    protected final static String DEFAULT_CONFIG_FILE = "jenkins-report.yml";


    @Symbol("performanceTest")
    @Extension
    public static class Descriptor extends BuildStepDescriptor<Builder> {

        @Override
        public boolean isApplicable(Class<? extends AbstractProject> jobType) {
            return true;
        }

        @Override
        public String getDisplayName() {
            return Messages.PerformanceTest_Name();
        }
    }


    private String params;
    private boolean printDebugOutput;
    private boolean useSystemSitePackages;

    @DataBoundConstructor
    public PerformanceTestBuild(String params, boolean printDebugOutput, boolean useSystemSitePackages) throws IOException {
        this.params = params;
        this.printDebugOutput = printDebugOutput;
        this.useSystemSitePackages = useSystemSitePackages;
    }


    @Override
    public void perform(@Nonnull Run<?, ?> run, @Nonnull FilePath workspace, @Nonnull Launcher launcher, @Nonnull TaskListener listener) throws InterruptedException, IOException {
        PrintStream logger = listener.getLogger();
        EnvVars envVars = run.getEnvironment(listener);

        boolean isVirtualenvInstallation = false;
        if (isGlobalBztInstalled(workspace, logger, launcher, envVars) ||
                (isVirtualenvInstallation = installBztAndCheck(workspace, logger, launcher, envVars))) {

            if (runPerformanceTest(workspace, logger, launcher, envVars, isVirtualenvInstallation)) {
                run.setResult(Result.SUCCESS);
                return;
            }
        }

        run.setResult(Result.FAILURE);
    }

    private boolean installBztAndCheck(FilePath workspace, PrintStream logger, Launcher launcher, EnvVars envVars) throws InterruptedException, IOException {
        return installBzt(workspace, logger, launcher, envVars) &&
                isVirtualenvBztInstalled(workspace, logger, launcher, envVars);
    }

    private boolean installBzt(FilePath workspace, PrintStream logger, Launcher launcher, EnvVars envVars) throws InterruptedException, IOException {
        return isVirtualenvInstalled(workspace, logger, launcher, envVars) &&
                createVirtualenvAndInstallBzt(workspace, logger, launcher, envVars);
    }

    private boolean createVirtualenvAndInstallBzt(FilePath workspace, PrintStream logger, Launcher launcher, EnvVars envVars) throws InterruptedException, IOException {
        return createIsolatedPython(workspace, logger, launcher, envVars) &&
                installBztInVirtualenv(workspace, logger, launcher, envVars);
    }

    // Step 1.1: Check bzt using "bzt --help".
    private boolean isGlobalBztInstalled(FilePath workspace, PrintStream logger, Launcher launcher, EnvVars envVars) throws InterruptedException, IOException {
        logger.println("Performance test: Checking global bzt installation...");
        boolean result = runCmd(CHECK_BZT_COMMAND, workspace, new NullOutputStream(), launcher, envVars);
        logger.println(result ?
                "Performance test: Found global bzt installation." :
                "Performance test: You don't have global bzt installed on this Jenkins host. Installing it globally will speed up job. Run 'sudo pip install bzt' to install it."
        );
        return result;
    }

    // Step 1.2: If bzt not installed check virtualenv using "virtualenv --help".
    private boolean isVirtualenvInstalled(FilePath workspace, PrintStream logger, Launcher launcher, EnvVars envVars) throws InterruptedException, IOException {
        logger.println("Performance test: Checking virtualenv tool availability...");
        final ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        boolean result = runCmd(CHECK_VIRTUALENV_COMMAND, workspace, outputStream, launcher, envVars);
        logger.println(result ?
                "Performance test: Found virtualenv tool." :
                "Performance test: No virtualenv found on this Jenkins host. Install it with 'sudo pip install virtualenv'."
        );
        if (!result || printDebugOutput) {
            logger.write(outputStream.toByteArray());
        }
        return result;
    }

    // Step 1.3: Create local python using "virtualenv --clear taurus-venv".
    private boolean createIsolatedPython(FilePath workspace, PrintStream logger, Launcher launcher, EnvVars envVars) throws InterruptedException, IOException {
        logger.println("Performance test: Creating virtualev at 'taurus-venv'...");
        final ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        boolean result = runCmd(useSystemSitePackages ?
                CREATE_LOCAL_PYTHON_COMMAND_WITH_SYSTEM_PACKAGES_OPTION :
                CREATE_LOCAL_PYTHON_COMMAND,
                workspace, outputStream, launcher, envVars);
        logger.println(result ?
                "Performance test: Done creating virtualenv." :
                "Performance test: Failed to create virtualenv at 'taurus-venv'"
        );
        if (!result || printDebugOutput) {
            logger.write(outputStream.toByteArray());
        }
        return result;
    }

    // Step 1.4: Install bzt in virtualenv using "taurus-venv/bin/pip install bzt".
    private boolean installBztInVirtualenv(FilePath workspace, PrintStream logger, Launcher launcher, EnvVars envVars) throws InterruptedException, IOException {
        logger.println("Performance test: Installing bzt into 'taurus-venv'");
        final ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        boolean result = runCmd(INSTALL_BZT_COMMAND, workspace, outputStream, launcher, envVars);
        logger.println(result ?
                "Performance test: bzt installed successfully." :
                "Performance test: Failed to install bzt into 'taurus-venv'"
        );
        if (!result || printDebugOutput) {
            logger.write(outputStream.toByteArray());
        }
        return result;
    }

    // Step 1.5: Check bzt using "taurus-venv/bin/bzt --help"
    private boolean isVirtualenvBztInstalled(FilePath workspace, PrintStream logger, Launcher launcher, EnvVars envVars) throws InterruptedException, IOException {
        logger.println("Performance test: Checking installed bzt...");
        final ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        boolean result = runCmd(CHECK_VIRTUALENV_BZT_COMMAND, workspace, outputStream, launcher, envVars);
        logger.println(result ?
                "Performance test: bzt is operational." :
                "Performance test: Failed to run bzt inside virtualenv."
        );
        if (!result || printDebugOutput) {
            logger.write(outputStream.toByteArray());
        }
        return result;
    }

    // Step 2: Run performance test.
    private boolean runPerformanceTest(FilePath workspace, PrintStream logger, Launcher launcher, EnvVars envVars, boolean isVirtualenvInstallation) throws InterruptedException, IOException {
        String[] params = this.params.split(" ");
        final List<String> testCommand = new ArrayList<String>(params.length + 2);
        testCommand.add((isVirtualenvInstallation ? VIRTUALENV_PATH : "") + PERFORMANCE_TEST_COMMAND);
        for (String param : params) {
            if (!param.isEmpty()) {
                testCommand.add(param);
            }
        }
        testCommand.add(extractDefaultReportToWorkspace(workspace));
        logger.println("Performance test: run " + Arrays.toString(testCommand.toArray()));
        return runCmd(testCommand.toArray(new String[testCommand.size()]), workspace, logger, launcher, envVars);
    }


    public boolean runCmd(String[] commands, FilePath workspace, OutputStream logger, Launcher launcher, EnvVars envVars) throws InterruptedException, IOException {
        try {
            return launcher.launch().cmds(commands).envs(envVars).stdout(logger).stderr(logger).pwd(workspace).start().join() == 0;
        } catch (IOException ex) {
            // TODO: write stacktrace to jenkins log
            logger.write(ex.getMessage().getBytes());
            if (printDebugOutput) {
                logger.write(Throwables.getStackTraceAsString(ex).getBytes());
            }
            return false;
        }
    }

    protected String extractDefaultReportToWorkspace(FilePath workspace) throws IOException, InterruptedException {
        FilePath defaultConfig = workspace.child(DEFAULT_CONFIG_FILE);
        defaultConfig.copyFrom(getClass().getResourceAsStream(DEFAULT_CONFIG_FILE));
        return defaultConfig.getRemote();
    }

    public String getParams() {
        return params;
    }

    @DataBoundSetter
    public void setParams(String params) {
        this.params = params;
    }

    public boolean isPrintDebugOutput() {
        return printDebugOutput;
    }

    @DataBoundSetter
    public void setPrintDebugOutput(boolean printDebugOutput) {
        this.printDebugOutput = printDebugOutput;
    }

    public boolean isUseSystemSitePackages() {
        return useSystemSitePackages;
    }

    @DataBoundSetter
    public void setUseSystemSitePackages(boolean useSystemSitePackages) {
        this.useSystemSitePackages = useSystemSitePackages;
    }
}
