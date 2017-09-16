package git.gitbisect;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.ExecutionException;

import hudson.model.Job;
import hudson.model.ParameterDefinition;
import hudson.model.ParameterValue;
import hudson.model.ParametersAction;
import hudson.model.ParametersDefinitionProperty;
import hudson.model.Result;
import hudson.model.Run;
import hudson.model.StringParameterValue;
import hudson.model.queue.QueueTaskFuture;
import jenkins.model.Jenkins;
import jenkins.model.ParameterizedJobMixIn;

public class CommitTester {
	@SuppressWarnings("rawtypes")
	private final class JobWrapper extends ParameterizedJobMixIn {
		@Override
		protected Job<?, ?> asJob() {
			return downstreamProj;
		}
	}

	private Run<?, ?> build;
	private Job<?, ?> downstreamProj;
	private String revisionParameterName;

	CommitTester(Run<?, ?> build, Job<?, ?> downstreamProj, String revisionParameterName) {
		this.build = build;
		this.downstreamProj = downstreamProj;
		this.revisionParameterName = revisionParameterName;
	}

	public boolean test(String commitToTest) throws InterruptedException {
		QueueTaskFuture<? extends Run<?, ?>> buildResult = runDownStreamProject(build, commitToTest);
		try {
			return getDownStreamResult(buildResult, commitToTest);
		} catch (ExecutionException e) {
			e.printStackTrace();
			Logger.error(
					"Downstream project threw an exception for revision " + commitToTest + " you may want to skip it");
			throw new DownstreamProjectCrashed();
		}
	}
	
	private QueueTaskFuture<? extends Run<?, ?>> runDownStreamProject(Run<?, ?> build, String commitId) {
		ArrayList<ParameterValue> combinedParameters = bubbleDownParameters(build, commitId);

		@SuppressWarnings("unchecked")
		QueueTaskFuture<? extends Run<?, ?>> buildResult =
				new JobWrapper().scheduleBuild2(-1, new ParametersAction(combinedParameters));
		return buildResult;
	}

	private boolean getDownStreamResult(QueueTaskFuture<? extends Run<?, ?>> buildResult, String commitToTest)
			throws InterruptedException, ExecutionException 
	{
		Result downstreamResult = buildResult.get().getResult();
		if (downstreamResult == null)
		{
			Logger.log("Downstream build had failed in an unknown manner");
			throw new DownstreamProjectCrashed();
		} else if (successfull(downstreamResult)) {
			Logger.log("Downstream build was succesful");
			return true;
		} else if (aborted(downstreamResult)) {
			Logger.log("Downstream build was aborted");
			throw new DownstreamProjectCrashed();
		} else {
			Logger.log("Downstream build had failed " + downstreamResult.toString());
			return false;
		}
	}

	private boolean aborted(Result downstreamResult) {
		return downstreamResult.equals(Result.ABORTED);
	}

	private boolean successfull(Result downstreamResult) {
		return downstreamResult.equals(Result.SUCCESS);
	}

	private ArrayList<ParameterValue> bubbleDownParameters(Run<?, ?> build, String commitId) {
		HashMap<String, ParameterValue> combinedParameters = new HashMap<>();
		addOwnParameters(build.getActions(ParametersAction.class), combinedParameters);
		combinedParameters.putAll(defaultJobParameters(downstreamProj));
		combinedParameters.put(revisionParameterName, new StringParameterValue(revisionParameterName, commitId));

		ArrayList<ParameterValue> result = new ArrayList<>();

		for (ParameterValue p : combinedParameters.values())
			result.add(p);

		return result;
	}

	private void addOwnParameters(List<ParametersAction> actions, HashMap<String, ParameterValue> combinedParameters) {
		for (ParametersAction parametersAction : actions) {
			for (ParameterValue parameterValue : parametersAction.getParameters()) {
				Logger.log("Aggregating parameter - " + parameterValue);
				combinedParameters.put(parameterValue.getName(), parameterValue);
			}
		}
	}

	private HashMap<String, ParameterValue> defaultJobParameters(Job<?, ?> downstreamProj) {
		ParametersDefinitionProperty paramDefProp = downstreamProj.getProperty(ParametersDefinitionProperty.class);

		HashMap<String, ParameterValue> defValues = new HashMap<>();

		/*
		 * This check is made ONLY if someone will call this method even if
		 * isParametrized() is false.
		 */
		if (paramDefProp == null)
			return defValues;

		/* Scan for all parameter with an associated default values */
		for (ParameterDefinition paramDefinition : paramDefProp.getParameterDefinitions()) {
			ParameterValue defaultValue = paramDefinition.getDefaultParameterValue();

			if (defaultValue != null) {
				Logger.log("Adding default value of parameter - " + defaultValue);
				defValues.put(paramDefinition.getName(), defaultValue);
			}
		}

		return defValues;
	}

	private static Job<?, ?> findDownStreamProject(String jobToRun) 
	{
		Logger.log("Looking for '" + jobToRun + "' as downstream project");
		Jenkins jenkins = Jenkins.getInstance();
		if (jenkins == null) return null;
		
		for (Job<?, ?> proj : jenkins.getAllItems(Job.class)) 
			if (proj.getName().equals(jobToRun))
				return proj;
		return null;
	}
	
	public static CommitTester buildFor(Run<?, ?> bisectBuild, String jobToRun, String revisionParameterName) {
		Job<?, ?> downstreamProject = findDownStreamProject(jobToRun);
		
    	if (downstreamProject == null)
    		throw new DownstreamProjectNotFound();
    	
		return new CommitTester(bisectBuild, downstreamProject, revisionParameterName);
	}
}
