/*
 * Copyright 2011-2017 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * Modifications copyright (C) 2017 HindsightSoftware.com/Matus Novak
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * A copy of the License is located at
 *
 *  http://aws.amazon.com/apache2.0
 *
 * or in the "license" file accompanying this file. This file is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.hindsightsoftware.upkeep;

import com.amazonaws.AmazonClientException;
import com.amazonaws.AmazonServiceException;
import com.amazonaws.auth.*;
import com.amazonaws.auth.profile.ProfileCredentialsProvider;
import com.amazonaws.regions.Region;
import com.amazonaws.regions.Regions;
import com.amazonaws.services.cloudformation.AmazonCloudFormation;
import com.amazonaws.services.cloudformation.AmazonCloudFormationClient;
import com.amazonaws.services.cloudformation.model.*;
import com.amazonaws.services.cloudformation.model.Stack;
import org.apache.maven.plugin.logging.Log;

import java.util.*;

/**
 * This is a modified version of CloudFormationSample.java from:
 * https://github.com/aws/aws-sdk-java/blob/master/src/samples/AwsCloudFormation/CloudFormationSample.java
 */
public class AwsCloudFormation {
    private final AWSCredentialsProviderChain credentials;
    private final Log log;
    private final AmazonCloudFormation cf;

    public AwsCloudFormation(Log log, String credentailsFilePath){
        this.log = log;
        this.credentials = new AWSCredentialsProviderChain(
                new PropertiesFileCredentialsProvider(credentailsFilePath),
                new EnvironmentVariableCredentialsProvider(),
                new SystemPropertiesCredentialsProvider(),
                new ProfileCredentialsProvider(),
                new InstanceProfileCredentialsProvider());
        this.cf = new AmazonCloudFormationClient(credentials);
    }

    public boolean build(String stackName, String templateUrl, String regionCode, String onFailure,
                         Map<String, String> parameters, Map<String, String> outputs, Map<String, String> resources) {

        try {
            cf.setRegion(Region.getRegion(Regions.fromName(regionCode)));
        } catch (IllegalArgumentException e){
            log.error("Invalid region code: " + regionCode);
            log.error("Expected single value from: " + Arrays.stream(Regions.values()).toString());
            return false;
        }

        log.info("Creating a stack called: \"" + stackName + "\" in a region: \"" + regionCode + "\"");

        try {
            // Check if stack has been previously created
            boolean skipCreation = false;
            for (Stack stack : cf.describeStacks(new DescribeStacksRequest()).getStacks()) {
                if(stack.getStackName().equals(stackName)){
                    skipCreation = true;
                    break;
                }
            }

            // Create a stack
            if(!skipCreation) {
                CreateStackRequest createRequest = new CreateStackRequest();
                createRequest.setStackName(stackName);
                createRequest.setTemplateURL(templateUrl);
                try {
                    createRequest.setOnFailure(OnFailure.fromValue(onFailure));
                } catch (IllegalArgumentException e) {
                    log.error("Invalid onfailure parameter: " + regionCode);
                    log.error("Expected single value from: " + Arrays.stream(OnFailure.values()).toString());
                    return false;
                }

                List<Parameter> parameterList = new ArrayList<Parameter>();

                for (Map.Entry<String, String> pair : parameters.entrySet()) {
                    parameterList.add(
                            new Parameter()
                                    .withParameterKey(pair.getKey())
                                    .withParameterValue(pair.getValue() == null ? "" : pair.getValue())
                    );
                }
                createRequest.setParameters(parameterList);

                log.info("Waiting... This may take up to 30 minutes.");
                cf.createStack(createRequest);
            } else {
                log.warn("Stack has been already created!");
            }

            // Wait for stack to be created
            // Note that you could use SNS notifications on the CreateStack call to track the progress of the stack creation
            log.info("Stack creation completed, the stack " + stackName + " completed with " + waitForCompletion(cf, stackName));

            // Show all the stacks for this account along with the resources for each stack
            Stack stack = cf.describeStacks(new DescribeStacksRequest()).getStacks().get(0);
            if(stack != null) {
                log.info("Stack : " + stack.getStackName() + " [" + stack.getStackStatus().toString() + "]");

                DescribeStackResourcesRequest stackResourceRequest = new DescribeStackResourcesRequest();
                stackResourceRequest.setStackName(stack.getStackName());
                for (StackResource resource : cf.describeStackResources(stackResourceRequest).getStackResources()) {
                    resources.put(resource.getLogicalResourceId(), resource.getPhysicalResourceId());
                    log.info(String.format("    %1$-40s %2$-25s %3$s", resource.getResourceType(), resource.getLogicalResourceId(), resource.getPhysicalResourceId()));
                }

                log.info("Outputs for stack : " + stack.getStackName());
                for (Output output : stack.getOutputs()){
                    outputs.put(output.getOutputKey(), output.getOutputValue());
                    log.info(String.format("    %1$s = %2$s (%3$s)", output.getOutputKey(), output.getOutputValue(), output.getDescription()));
                }
            }

        } catch (AmazonServiceException ase){
            AwsUtils.printAmazonServiceException(log, ase);
            return false;

        } catch (AmazonClientException ace) {
            AwsUtils.printAmazonClientException(log, ace);
            return false;

        } catch (InterruptedException iex){
            log.error("Error while Thread.sleep() " + iex.getMessage());
            return false;
        }

        return true;
    }

    public boolean stop(String stackName){
        try {
            // Delete the stack
            DeleteStackRequest deleteRequest = new DeleteStackRequest();
            deleteRequest.setStackName(stackName);
            log.info("Deleting the stack called " + deleteRequest.getStackName() + ".");
            cf.deleteStack(deleteRequest);

            // Wait for stack to be deleted
            // Note that you could used SNS notifications on the original CreateStack call to track the progress of the stack deletion
            log.info("Stack creation completed, the stack " + stackName + " completed with " + waitForCompletion(cf, stackName));

        } catch (AmazonServiceException ase){
            if(ase.getStatusCode() == 400 && ase.getErrorMessage().contains("does not exist")){
                log.info("Stack deleted");
                return true;
            }
            AwsUtils.printAmazonServiceException(log, ase);
            return false;

        } catch (AmazonClientException ace) {
            AwsUtils.printAmazonClientException(log, ace);
            return false;

        } catch (InterruptedException iex){
            log.error("Error while Thread.sleep() " + iex.getMessage());
            return false;
        }

        return true;
    }

    // Wait for a stack to complete transitioning
    // End stack states are:
    //    CREATE_COMPLETE
    //    CREATE_FAILED
    //    DELETE_FAILED
    //    ROLLBACK_FAILED
    // OR the stack no longer exists
    public String waitForCompletion(AmazonCloudFormation stackbuilder, String stackName) throws InterruptedException {

        DescribeStacksRequest wait = new DescribeStacksRequest();
        wait.setStackName(stackName);
        Boolean completed = false;
        String  stackStatus = "Unknown";
        String  stackReason = "";

        Map<String, String> resourceStatusMap = new HashMap<String, String>();

        while (!completed) {
            List<Stack> stacks = stackbuilder.describeStacks(wait).getStacks();
            if (stacks.isEmpty())
            {
                completed   = true;
                stackStatus = "NO_SUCH_STACK";
                stackReason = "Stack has been deleted";
            } else {
                for (Stack stack : stacks) {
                    if (stack.getStackStatus().equals(StackStatus.CREATE_COMPLETE.toString()) ||
                            stack.getStackStatus().equals(StackStatus.CREATE_FAILED.toString()) ||
                            stack.getStackStatus().equals(StackStatus.ROLLBACK_FAILED.toString()) ||
                            stack.getStackStatus().equals(StackStatus.DELETE_FAILED.toString())) {
                        completed = true;
                        stackStatus = stack.getStackStatus();
                        stackReason = stack.getStackStatusReason();
                    }

                    // Print out status of resources
                    DescribeStackResourcesRequest stackResourceRequest = new DescribeStackResourcesRequest();
                    stackResourceRequest.setStackName(stack.getStackName());
                    for (StackResource resource : stackbuilder.describeStackResources(stackResourceRequest).getStackResources()) {
                        String name = resource.getLogicalResourceId();
                        if (!resourceStatusMap.containsKey(name) || !resourceStatusMap.get(name).equals(resource.getResourceStatus())) {
                            String status = resource.getResourceStatus();
                            String statusReason = resource.getResourceStatusReason();

                            log.info(name + " " + status + (statusReason != null ? " ( " + statusReason + " )" : ""));
                            resourceStatusMap.put(name, resource.getResourceStatus());
                        }
                    }
                }
            }

            // Not done yet so sleep for 10 seconds.
            if (!completed) Thread.sleep(10000);
        }

        return stackStatus + " (" + stackReason + ")";
    }
}
