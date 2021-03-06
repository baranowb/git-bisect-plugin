package git.bisect.builder;
import java.io.IOException;
import java.util.HashMap;

import org.jenkinsci.Symbol;
import org.kohsuke.stapler.DataBoundConstructor;

import git.bisect.CommitPair;
import git.bisect.Logger;
import git.bisect.builder.CommandsRunner.BisectionResult;
import git.bisect.builder.CommandsRunner.CommandOutput;
import git.bisect.builder.CommandsRunner.CommitState;
import hudson.EnvVars;
import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.AbstractProject;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.Builder;
import jenkins.tasks.SimpleBuildStep;

public class GitBisectBuilder extends Builder implements SimpleBuildStep {

    final String jobToRun;
	final String goodStartCommit;
	final String badEndCommit;
	final String searchIdentifier;
	final String revisionParameterName;
	final int retryCount;
	final boolean continuesBuild;
	final int minSuccessfulIterations;
	final boolean overrideGitCommand;
	final String gitCommand;

    transient BisectConfiguration configuration;
	transient CommandsRunner helper;
	transient CommitTester commitTester;
	transient EnvVars envVars;
	transient CommitPair commitRange;
	
	// DataBoundConstructor is for the jelly config file
	@DataBoundConstructor
    public GitBisectBuilder(
    		String jobToRun, 
    		String goodStartCommit, 
    		String badEndCommit, 
    		String searchIdentifier,
    		String revisionParameterName,
    		int retryCount, 
    		boolean continuesBuild,
    		int minSuccessfulIterations,
    		boolean overrideGitCommand,
    		String gitCommand) {
		this.jobToRun = jobToRun.trim();
		this.goodStartCommit = goodStartCommit.trim();
		this.badEndCommit = badEndCommit.trim();
		this.searchIdentifier = searchIdentifier.trim();
		this.revisionParameterName = revisionParameterName;
		this.retryCount = retryCount;
		this.continuesBuild = continuesBuild;
		this.minSuccessfulIterations = minSuccessfulIterations;
		this.overrideGitCommand = overrideGitCommand;
		this.gitCommand = gitCommand;
    }

	private String expand(String str) 
	{
		return envVars.expand(str);
	}
	
    @Override
    public void perform(Run<?,?> build, FilePath workspace, Launcher launcher, TaskListener listener) throws IOException, InterruptedException{
    	Logger.initializeLogger(listener);
    	Logger.log("Initializing");
    	envVars = build.getEnvironment(listener);
    	String jobToRun = expand(this.jobToRun);
    	String searchIdentifier = expand(this.searchIdentifier);
    	String gitCommand = expand(this.gitCommand);
    	this.commitRange = new CommitPair(
    			expand(this.goodStartCommit),
    			expand(this.badEndCommit));
    	
    	this.helper = new CommandsRunner(build, workspace, launcher, listener, gitCommand);
    	this.configuration = new BisectConfiguration(build, workspace, listener, searchIdentifier);
    	this.commitTester = CommitTester.buildFor(build, jobToRun);
    	
		Logger.log("Git command that will be used is: '" + gitCommand + "'");
		
		try
		{
			runBisection();
		} catch (Exception e)
		{
			Logger.log("Cought exception - bisect stopping");
			e.printStackTrace();
		}
		finally {
			configuration.cleanup();
			// This solves some annoying problems 
			// Git sometimes fails to delete a branch while bisecting
			// It tries to do this while fetching, so it's before the next build starts
			helper.resetBisection();
		}
    }

	private void runBisection() throws IOException, InterruptedException {
		BisectionResult bisectResult = startBisecting();
		
		if (bisectResult.isDone) {
			Logger.log("This search identifier has already completed, did you forget changing it in project configuration?\n" + 
					   "if you forgot what the bad commit was, here it is - \n" + bisectResult.commit);
			return;
		}
		
		do {
			bisectResult = run(bisectResult.commit);
			copyResultsToMaster();
		} while (continuesBuild && !bisectResult.isDone);
		
		if (bisectResult.isDone)
			Logger.log("Bisect completed, wanted revision is - " + bisectResult.commit);
	}

    static class RevisionClassifier
    {
    	private int remainingFailures;
		private int remainingSuccessfulIterations;

		public RevisionClassifier(int retryCount, int minSuccessfulIterations)
    	{
			this.remainingFailures = retryCount;
			this.remainingSuccessfulIterations = minSuccessfulIterations;
    	}
		
		public boolean verifiedResult()
		{
			return remainingFailures == 0 ||
				   remainingSuccessfulIterations == 0;
		}
		
		public void updateResult(boolean wasSuccessful)
		{
			if (wasSuccessful)
				remainingSuccessfulIterations -= 1;
			else 
				remainingFailures -= 1;
			
			Logger.log("Remaining failures: " + remainingFailures + 
					   " , remaining successful runs: " + remainingSuccessfulIterations);
		}

		public boolean wasGood() {
			return remainingSuccessfulIterations == 0;
		}
    }
    
	private BisectionResult run(String commit) throws InterruptedException, IOException {
		Logger.log("Running against revision - " + commit);
		
		int neededFailureNumber = retryCount + 1;
		
		RevisionClassifier buildResult = new RevisionClassifier(neededFailureNumber, minSuccessfulIterations);
		do
		{
			Logger.log("Running downstream project with revision = '" + commit +"'");
			String revisionParameterName = expand(this.revisionParameterName);
			buildResult.updateResult(commitTester.test(withBisectParams(revisionParameterName, commit)));
		}
		while (!buildResult.verifiedResult());
		
		return helper.markCommitAs(commit, CommitState.fromBool(buildResult.wasGood()));
	}

    private void copyResultsToMaster()
			throws IOException, InterruptedException {
    	Logger.log("Copying results to master");
    	
    	CommandOutput bisectionLog = helper.getBisectionLog();
    	
    	if (bisectionLog.exitStatus != 0)
    	{
    		throw new RuntimeException(
    				"Running 'git bisect log' failed with the following details:\n" +
    				"exitStatus = " + bisectionLog.exitStatus + "\n" + 
    				"stdout = '" + bisectionLog.stdout + "'\n" + 
    				"stderr = '" + bisectionLog.stderr + "'");
    	}
    	
    	configuration.saveContent(bisectionLog.stdout + "\n");
	}

	private BisectionResult startBisecting() throws IOException, InterruptedException {
		helper.resetBisection();
		helper.startBisection();
        
        return runInitialBisection();
	}

	private BisectionResult runInitialBisection() throws IOException, InterruptedException {
		if (configuration.hasPreviousConfiguration())
			return bisectWithPreviousResults();
        else
        	return bisectWithGivenInput();
	}

	private BisectionResult bisectWithPreviousResults() throws IOException, InterruptedException {
		return helper.bisectFromFile(configuration.localFile());
	}

	private BisectionResult bisectWithGivenInput() throws IOException, InterruptedException {
		if (!validInput())
			throw new RuntimeException(
					"Invalid input given, "
					+ "either one of the revisions does not exist or the git configuration is malformed. "
					+ "Check the previous log lines for more information.");
		
		helper.markCommitAs(commitRange.badCommit, CommitState.Bad);
		return 
			helper.markCommitAs(commitRange.goodCommit, CommitState.Good);
	}
	
	private boolean validInput() throws IOException, InterruptedException {
		// TODO: needs to check that goodStart comes before badEnd
		return helper.checkExistance(commitRange.badCommit) && 
			   helper.checkExistance(commitRange.goodCommit);
	}

    // Overridden for better type safety.
    @Override
    public DescriptorImpl getDescriptor() {
        return (DescriptorImpl)super.getDescriptor();
    }

    public String getJobToRun() {
		return jobToRun;
	}

	public String getBadEndCommit() {
		return badEndCommit;
	}

	public String getGoodStartCommit() {
		return goodStartCommit;
	}
	
	public String getSearchIdentifier()	{
		return searchIdentifier;
	}
	
	public String getRevisionParameterName() {
		return revisionParameterName;
	}
	
	public boolean getContinuesBuild() {
		return continuesBuild;
	}
	
	public int getRetryCount() {
		return retryCount;
	}
	
	public int getMinSuccessfulIterations() {
		return minSuccessfulIterations;
	}
	
	public boolean getOverrideGitCommand() {
		return overrideGitCommand;
	}
	
	public String getGitCommand() {
		return gitCommand;
	}
	
	public static HashMap<String, String> withBisectParams(String revisionParameterName, String commit) {
		HashMap<String, String> bisectParams = new HashMap<>();
		bisectParams.put(revisionParameterName, commit);
		return bisectParams;
	}

    @Extension
    @Symbol("gitbisect")
    public static final class DescriptorImpl extends BuildStepDescriptor<Builder> {
        public boolean isApplicable(Class<? extends AbstractProject> aClass) {
            return true;
        }

        public String getDisplayName() {
            return "Git Bisect";
        }
    }
}

