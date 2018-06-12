package com.behindmedia.allure.plugin.xcode;

import io.qameta.allure.Reader;
import io.qameta.allure.core.Configuration;
import io.qameta.allure.core.ResultsVisitor;
import io.qameta.allure.entity.*;
import org.apache.commons.configuration2.ex.ConfigurationException;
import org.apache.commons.configuration2.plist.XMLPropertyListConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static io.qameta.allure.entity.LabelName.RESULT_FORMAT;
import static java.nio.file.Files.newDirectoryStream;
import static java.util.Objects.isNull;
import static java.util.Objects.nonNull;

public class XcodeResultsBundlePlugin implements Reader {

    private static final Logger LOGGER = LoggerFactory.getLogger(XcodeResultsBundlePlugin.class);
    private static final String XCODE_RESULTS_FORMAT = "xcode";

    @Override
    public void readResults(Configuration configuration, ResultsVisitor visitor, Path directory) {
        try {
            listResults(directory).forEach(resultFile -> processTestSummaries(resultFile, visitor));
        } catch (Exception e) {
            LOGGER.error("Could not parse bundle at path {}: {}", directory, e);
        }
    }

    private void processTestSummaries(final Path parsedFile, final ResultsVisitor visitor) {
        try {
            LOGGER.info("Parsing file {}", parsedFile);

            XMLPropertyListConfiguration plist = readPlist(parsedFile.toFile());
            List<XMLPropertyListConfiguration> testSummaries = plist.getList(XMLPropertyListConfiguration.class, "TestableSummaries", Collections.emptyList());
            testSummaries.forEach(testSummary -> {
                processTestSummary(testSummary, parsedFile, visitor);
            });

        } catch (Exception e) {
            LOGGER.error("Could not parse file {}: {}", parsedFile, e);
        }
    }

    private void processTestSummary(final XMLPropertyListConfiguration testableSummary, final Path parsedFile, final ResultsVisitor visitor) {

        final String testSuiteName = testableSummary.getString("TestName");

        final List<XMLPropertyListConfiguration> testConfigs = testableSummary.getList(XMLPropertyListConfiguration.class, "Tests",
                Collections.emptyList());

        testConfigs.forEach(testConfig -> {
            processTestObject(testSuiteName, testConfig, Optional.empty(), parsedFile, visitor);
        });
    }

    private void processTestObject(final String testSuiteName, final XMLPropertyListConfiguration config, final Optional<XMLPropertyListConfiguration> parentConfig, final Path parsedFile, final ResultsVisitor visitor) {

        final String testObjectClass = config.getString("TestObjectClass");

        if ("IDESchemeActionTestSummaryGroup".equals(testObjectClass)) {
            config.getList(XMLPropertyListConfiguration.class, "Subtests", Collections.emptyList()).forEach(subConfig -> {
                processTestObject(testSuiteName, subConfig, Optional.of(config), parsedFile, visitor);
            });
        } else if ("IDESchemeActionTestSummary".equals(testObjectClass)) {

            if (parentConfig.isPresent()) {
                processTest(testSuiteName, config, parentConfig.get().getString("TestName"), parsedFile, visitor);
            } else {
                LOGGER.debug("Invalid config: parent is null");
            }

        } else {
            //Unrecognized
            LOGGER.debug("Unrecognized testObjectClass: {}", testObjectClass);
        }
    }

    private void processTest(final String testSuiteName, final XMLPropertyListConfiguration testConfig, final String testGroup, final Path parsedFile, final ResultsVisitor visitor) {

        final String testIdentifier = testConfig.getString("TestIdentifier");
        final String testName = testConfig.getString("TestName");
        final String historyId = nonNull(testGroup) && nonNull(testName) ? String.format("%s:%s#%s", testSuiteName, testGroup, testName) : null;
        final TestResult result = new TestResult();

        result.setTestId(testIdentifier);
        result.setHistoryId(historyId);
        result.setName(isNull(testName) ? "Unknown test case" : testName);
        result.addLabelIfNotExists(RESULT_FORMAT, XCODE_RESULTS_FORMAT);
        result.addLabelIfNotExists(LabelName.SUITE, testGroup);

        if (nonNull(testGroup)) {
            result.addLabelIfNotExists(LabelName.TEST_CLASS, testGroup);
            result.addLabelIfNotExists(LabelName.PACKAGE, testGroup);
        }

        final String testGUID = testConfig.getString("TestSummaryGUID");

        result.setUid(testGUID);
        result.setFlaky(ParserUtils.isFlaky(testConfig));

        final List<XMLPropertyListConfiguration> activities = testConfig.getList(XMLPropertyListConfiguration.class, "ActivitySummaries", Collections.emptyList());
        final List<Step> failedSteps = new ArrayList<>();

        final ActivityResult activityResult = processActivities(activities, failedSteps, parsedFile, visitor);
        final List<Step> steps = activityResult.getSteps();
        final Status status = activityResult.getStatus();

        StageResult stageResult = new StageResult();
        stageResult.setStatus(status);
        stageResult.setSteps(steps);

        final int stepCount = stageResult.getSteps().size();
        final Time testTime;

        if (stepCount > 0) {
            final Step firstStep = steps.get(0);
            final Step lastStep = steps.get(stepCount - 1);

            final Long startTime = firstStep.getTime().getStart();
            final Long endTime = lastStep.getTime().getStop();

            testTime = Time.create(startTime, endTime);
        } else {
            testTime = Time.create(ParserUtils.getTimeInMilliseconds(testConfig.getDouble("Duration")));
        }

        stageResult.setTime(testTime);
        stageResult.setName(testName);

        result.setTime(testTime);
        result.setTestStage(stageResult);
        result.setStatus(stageResult.getStatus());

        final StringBuilder builder = new StringBuilder();

        failedSteps.forEach(step -> {
            final String failureMessage = step.getStatusMessage();
            if (builder.length() > 0) {
                builder.append("\n\n");
            }

            builder.append(failureMessage);
        });

        if (builder.length() > 0) {
            result.setStatusMessage(builder.toString());
        }
        visitor.visitTestResult(result);
    }

    private ActivityResult processActivities(final List<XMLPropertyListConfiguration> activities, final List<Step> failedSteps, final Path parsedFile, final ResultsVisitor visitor) {

        List<Step> allSteps = new ArrayList<>();
        Status effectiveStatus = Status.PASSED;

        final Path attachmentDir = parsedFile.getParent().resolve("Attachments");

        for (XMLPropertyListConfiguration activityConfig: activities) {

            final String activityType = activityConfig.getString("ActivityType");
            final boolean isAssertionFailure = "com.apple.dt.xctest.activity-type.testAssertionFailure".equals(activityType);
            final boolean isCrash = activityConfig.getString("DiagnosticReportFileName", null) != null;

            final List<XMLPropertyListConfiguration> attachmentConfigs = activityConfig.getList(XMLPropertyListConfiguration.class, "Attachments", Collections.emptyList());

            final Double startTime = activityConfig.getDouble("StartTimeInterval");
            final Double endTime = activityConfig.getDouble("FinishTimeInterval", startTime);
            final String title = activityConfig.getString("Title");

            final Step step = new Step();
            step.setTime(ParserUtils.getTime(startTime, endTime - startTime));
            step.setName(title);

            List<XMLPropertyListConfiguration> subActivities = activityConfig.getList(XMLPropertyListConfiguration.class, "SubActivities", Collections.emptyList());
            ActivityResult subResult = processActivities(subActivities, failedSteps, parsedFile, visitor);

            step.setSteps(subResult.getSteps());

            final List<Attachment> attachments = attachmentConfigs.stream().flatMap(attachmentConfig -> {
                final String filename = attachmentConfig.getString("Filename");
                return resolveAttachmentPath(attachmentDir, filename)
                        .map(visitor::visitAttachmentFile)
                        .map(Stream::of)
                        .orElse(Stream.empty());
            }).collect(Collectors.toList());

            step.setAttachments(attachments);

            final Status activityStatus;

            if (isCrash) {
                activityStatus = Status.FAILED;
                step.setStatusMessage(title);
                failedSteps.add(step);
            } else if (isAssertionFailure) {
                activityStatus = Status.BROKEN;
                step.setStatusMessage(title);
                failedSteps.add(step);
            } else if (!step.getSteps().isEmpty()) {
                activityStatus = subResult.getStatus();
            } else {
                activityStatus = Status.PASSED;
            }

            step.setStatus(activityStatus);

            if (activityStatus == Status.FAILED) {
                effectiveStatus = Status.FAILED;
            } else if (activityStatus == Status.BROKEN && effectiveStatus != Status.FAILED) {
                effectiveStatus = Status.BROKEN;
            }
            allSteps.add(step);
        }

        return new ActivityResult(effectiveStatus, allSteps);
    }


    private static XMLPropertyListConfiguration readPlist(File file) throws IOException, ConfigurationException {
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            XMLPropertyListConfiguration plist = new XMLPropertyListConfiguration();
            plist.read(reader);
            return plist;
        }
    }

    private static List<Path> listResults(final Path directory) {
        List<Path> result = new ArrayList<>();
        if (!Files.isDirectory(directory)) {
            return result;
        }

        try (DirectoryStream<Path> directoryStream = newDirectoryStream(directory, "*TestSummaries.plist")) {
            for (Path path : directoryStream) {
                if (!Files.isDirectory(path)) {
                    result.add(path);
                }
            }
        } catch (IOException e) {
            LOGGER.error("Could not read data from {}: {}", directory, e);
        }
        return result;
    }

    private Optional<Path> resolveAttachmentPath(final Path rootDirectory, final String attachmentName) {
        try {
            return Optional.ofNullable(attachmentName)
                    .map(rootDirectory::resolve).filter(Files::exists);
        } catch (InvalidPathException e) {
            LOGGER.debug("Can not find attachment: {}", attachmentName, e);
            return Optional.empty();
        }
    }

    private static class ActivityResult {
        private final Status status;
        private final List<Step> steps;

        ActivityResult(Status status, List<Step> steps) {
            this.status = status;
            this.steps = steps;
        }

        Status getStatus() {
            return status;
        }

        List<Step> getSteps() {
            return steps;
        }
    }
}

