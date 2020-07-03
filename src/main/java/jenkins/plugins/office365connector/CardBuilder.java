/**
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package jenkins.plugins.office365connector;

import hudson.model.Cause;
import hudson.model.Result;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.tasks.test.AbstractTestResultAction;
import jenkins.plugins.office365connector.model.Card;
import jenkins.plugins.office365connector.model.FactDefinition;
import jenkins.plugins.office365connector.model.Section;
import jenkins.plugins.office365connector.workflow.StepParameters;

import java.util.Collections;
import java.util.List;

import static hudson.Util.getTimeSpanString;
import static java.util.stream.Collectors.joining;

/**
 * @author Damian Szczepanik (damianszczepanik@github)
 */
public class CardBuilder {

    private final Run run;

    private final FactsBuilder factsBuilder;
    private final ActionableBuilder potentialActionBuilder;

    public CardBuilder(Run run, TaskListener taskListener) {
        this.run = run;

        factsBuilder = new FactsBuilder(run, taskListener);
        potentialActionBuilder = new ActionableBuilder(run, factsBuilder);
    }

    public Card createStartedCard(List<FactDefinition> factDefinitions) {
        final String statusName = "Started";
        factsBuilder.addStatus(statusName);
        factsBuilder.addRemarks();
        factsBuilder.addCommitters();
        factsBuilder.addDevelopers();
        factsBuilder.addUserFacts(factDefinitions);

        Section section = buildSection(statusName, "");

        String summary = getDisplayName() + ": Build " + getRunName();
        Card card = new Card(summary, section);
        card.setPotentialAction(potentialActionBuilder.buildActionable());

        return card;
    }

    public Card createCompletedCard(List<FactDefinition> factDefinitions) {
        // result might be null only for ongoing job - check documentation of Run.getCompletedResult()
        // but based on issue #133 it may happen that result for completed job is null
        Result lastResult = getCompletedResult(run);

        Run previousBuild = run.getPreviousBuild();
        Result previousResult = previousBuild != null ? previousBuild.getResult() : Result.SUCCESS;
        Run lastNotFailedBuild = run.getPreviousNotFailedBuild();

        boolean isRepeatedFailure = isRepeatedFailure(previousResult, lastNotFailedBuild);
        String summary = String.format("%s: Build %s %s", getDisplayName(), getRunName(),
                calculateSummary(lastResult, previousResult, isRepeatedFailure));
        String status = calculateStatus(lastResult, previousResult, isRepeatedFailure);

        if (lastResult == Result.FAILURE) {
            Run failingSinceBuild = getFailingSinceBuild(lastNotFailedBuild);

            if (failingSinceBuild != null && previousResult == Result.FAILURE) {
                factsBuilder.addFailingSinceBuild(failingSinceBuild.getNumber());
            }
        }

        factsBuilder.addStatus(status);
        factsBuilder.addRemarks();
        factsBuilder.addCommitters();
        factsBuilder.addDevelopers();
        factsBuilder.addUserFacts(factDefinitions);

        String duration = getDuration(isRepeatedFailure);
        Section section = buildSection(status, duration);

        Card card = new Card(summary, section);
        card.setThemeColor(getCardThemeColor(lastResult));
        card.setPotentialAction(potentialActionBuilder.buildActionable());

        return card;
    }

    private static String getCardThemeColor(Result result) {
        if (result == Result.SUCCESS) {
            // Return slack green for success
            return "#2eb886";
        } else if (result == Result.FAILURE) {
            // Return slack red for failures
            return "#a3020c";
        } else {
            return result.color.getHtmlBaseColor();
        }
    }

    private Section buildSection(String status, String duration) {
        String durStr = "";
        if (duration != null && !duration.isEmpty()) {
            durStr = " after " + duration;
        }
        String activityTitle = "_" + getEscapedDisplayName() + "_ - " + getRunName() + " *" + status + "*" + durStr;
        String activitySubtitle = getTestSummary() + "\\n\\n" + getCauseSummary();
        return new Section(activityTitle, activitySubtitle, Collections.emptyList());
    }

    private String getCauseSummary() {
        List<Cause> causes = run.getCauses();

        return causes.stream()
                .map(cause -> cause.getShortDescription().concat(","))
                .collect(joining(" "));
    }

    private String getDuration(boolean isRepeatedFailure) {
        String durationString;
        if (isRepeatedFailure) {
            durationString = createBackToNormalDurationString();
        } else {
            durationString = run.getDurationString();
        }
        return durationString;
    }

    private String createBackToNormalDurationString() {
        // This status code guarantees that the previous build fails and has been successful before
        // The back to normal time is the time since the build first broke
        Run previousSuccessfulBuild = run.getPreviousSuccessfulBuild();
        if (null != previousSuccessfulBuild && null != previousSuccessfulBuild.getNextBuild()) {
            Run initialFailureAfterPreviousSuccessfulBuild = previousSuccessfulBuild.getNextBuild();
            if (initialFailureAfterPreviousSuccessfulBuild != null) {
                long initialFailureStartTime = initialFailureAfterPreviousSuccessfulBuild.getStartTimeInMillis();
                long initialFailureDuration = initialFailureAfterPreviousSuccessfulBuild.getDuration();
                long initialFailureEndTime = initialFailureStartTime + initialFailureDuration;
                long buildStartTime = run.getStartTimeInMillis();
                long buildDuration = run.getDuration();
                long buildEndTime = buildStartTime + buildDuration;
                long backToNormalDuration = buildEndTime - initialFailureEndTime;
                return getTimeSpanString(backToNormalDuration);
            }
        }
        return null;
    }

    public String getTestSummary() {
        AbstractTestResultAction<?> action = this.run
                .getAction(AbstractTestResultAction.class);
        if (action != null) {
            int total = action.getTotalCount();
            int failed = action.getFailCount();
            int skipped = action.getSkipCount();
            return "Test status: passed " + (total - failed - skipped) + ", failed: " + failed + ", skipped: " + skipped;
        } else {
            return "No tests found.";
        }
    }

    private boolean isRepeatedFailure(Result previousResult, Run lastNotFailedBuild) {
        Run failingSinceRun = getFailingSinceBuild(lastNotFailedBuild);

        return failingSinceRun != null && previousResult == Result.FAILURE;
    }

    private Run getFailingSinceBuild(Run lastNotFailedBuild) {
        return lastNotFailedBuild != null
                ? lastNotFailedBuild.getNextBuild() : run.getParent().getFirstBuild();
    }

    String calculateStatus(Result lastResult, Result previousResult, boolean isRepeatedFailure) {
        if (lastResult == Result.SUCCESS) {
            // back to normal
            if (previousResult == Result.FAILURE || previousResult == Result.UNSTABLE) {
                return "Back to Normal";
            }
            // success remains
            return "Build Success";
        }
        if (lastResult == Result.FAILURE) {
            if (isRepeatedFailure) {
                return "Repeated Failure";
            }
            return "Build Failed";
        }
        if (lastResult == Result.ABORTED) {
            return "Build Aborted";
        }
        if (lastResult == Result.UNSTABLE) {
            return "Build Unstable";
        }

        return lastResult.toString();
    }

    String calculateSummary(Result completedResult, Result previousResult, boolean isRepeatedFailure) {

        if (completedResult == Result.SUCCESS) {
            // back to normal
            if (previousResult == Result.FAILURE || previousResult == Result.UNSTABLE) {
                return "Back to Normal";
            }
            // success remains
            return "Success";
        }
        if (completedResult == Result.FAILURE) {
            if (isRepeatedFailure) {
                return "Repeated Failure";
            }
            return "Failed";
        }
        if (completedResult == Result.ABORTED) {
            return "Aborted";
        }
        if (completedResult == Result.UNSTABLE) {
            return "Unstable";
        }

        return completedResult.toString();
    }

    // this is tricky way to avoid findBugs NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE
    // which is not true in that case
    private Result getCompletedResult(Run run) {
        return run.getResult() == null ? Result.SUCCESS : run.getResult();
    }

    public Card createBuildMessageCard(StepParameters stepParameters) {
        if (stepParameters.getStatus() != null) {
            factsBuilder.addStatus(stepParameters.getStatus());
        }
        factsBuilder.addUserFacts(stepParameters.getFactDefinitions());

        String activityTitle = "Notification from " + getEscapedDisplayName();
        Section section = new Section(activityTitle, stepParameters.getMessage(), factsBuilder.collect());

        String summary = getDisplayName() + ": Build " + getRunName();
        Card card = new Card(summary, section);

        if (stepParameters.getColor() != null) {
            card.setThemeColor(stepParameters.getColor());
        }

        card.setPotentialAction(potentialActionBuilder.buildActionable());

        return card;
    }

    /**
     * Returns escaped name of the job presented as display name with parent name such as folder.
     * Parent is needed for multi-branch pipelines and for cases when job
     */
    private String getEscapedDisplayName() {
        String displayName = getDisplayName();
        // escape special characters so the summary is not formatted
        // when the build name contains special characters
        // https://www.markdownguide.org/basic-syntax#characters-you-can-escape
        return displayName.replaceAll("([*_#-])", "\\\\$1");
    }

    /**
     * Returns name of the project.
     */
    private String getDisplayName() {
        return run.getParent().getFullDisplayName();
    }

    private String getRunName() {
        // TODO: test case when the build number is changed to custom name
        return run.hasCustomDisplayName() ? run.getDisplayName() : "#" + run.getNumber();
    }
}
