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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * This is a modified version of CloudFormationSample.java from:
 * https://github.com/aws/aws-sdk-java/blob/master/src/samples/AwsCloudFormation/CloudFormationSample.java
 */
public class CloudFormation {
    private final AWSCredentialsProviderChain credentials;
    private final String logicalResourceName = "SampleNotificationTopic";

    private AmazonCloudFormation stackbuilder;

    public CloudFormation(String credentailsFilePath){
        credentials = new AWSCredentialsProviderChain(
                new PropertiesFileCredentialsProvider(credentailsFilePath),
                new EnvironmentVariableCredentialsProvider(),
                new SystemPropertiesCredentialsProvider(),
                new ProfileCredentialsProvider(),
                new InstanceProfileCredentialsProvider());
    }

    public boolean build(String stackName, String templateUrl, String regionCode, Map<String, String> parameters) {
        stackbuilder = new AmazonCloudFormationClient(credentials);

        try {
            stackbuilder.setRegion(Region.getRegion(Regions.fromName(regionCode)));
        } catch (IllegalArgumentException e){
            System.err.println("Invalid region code: " + regionCode);
            System.err.println("Expected single value from: " + Arrays.stream(Regions.values()).toString());
            return false;
        }

        System.out.println("Creating a stack called: \"" + stackName + "\" in a region: \"" + regionCode + "\"");

        try {
            // Create a stack
            CreateStackRequest createRequest = new CreateStackRequest();
            createRequest.setStackName(stackName);
            createRequest.setTemplateURL(templateUrl);
            createRequest.setOnFailure(OnFailure.DO_NOTHING);

            List<Parameter> parameterList = new ArrayList<Parameter>();

            for(Map.Entry<String, String> pair : parameters.entrySet()){
                parameterList.add(
                        new Parameter()
                                .withParameterKey(pair.getKey())
                                .withParameterValue(pair.getValue() == null ? "" : pair.getValue())
                );
            }
            createRequest.setParameters(parameterList);

            System.out.println("Waiting... This may take up to 30 minutes.");
            stackbuilder.createStack(createRequest);

            // Wait for stack to be created
            // Note that you could use SNS notifications on the CreateStack call to track the progress of the stack creation
            System.out.println("Stack creation completed, the stack " + stackName + " completed with " + waitForCompletion(stackbuilder, stackName));

            // Show all the stacks for this account along with the resources for each stack
            for (Stack stack : stackbuilder.describeStacks(new DescribeStacksRequest()).getStacks()) {
                System.out.println("Stack : " + stack.getStackName() + " [" + stack.getStackStatus().toString() + "]");

                DescribeStackResourcesRequest stackResourceRequest = new DescribeStackResourcesRequest();
                stackResourceRequest.setStackName(stack.getStackName());
                for (StackResource resource : stackbuilder.describeStackResources(stackResourceRequest).getStackResources()) {
                    System.out.format("    %1$-40s %2$-25s %3$s\n", resource.getResourceType(), resource.getLogicalResourceId(), resource.getPhysicalResourceId());
                }
            }

            // Lookup a resource by its logical name
            DescribeStackResourcesRequest logicalNameResourceRequest = new DescribeStackResourcesRequest();
            logicalNameResourceRequest.setStackName(stackName);
            logicalNameResourceRequest.setLogicalResourceId(logicalResourceName);
            System.out.format("Looking up resource name %1$s from stack %2$s\n", logicalNameResourceRequest.getLogicalResourceId(), logicalNameResourceRequest.getStackName());
            for (StackResource resource : stackbuilder.describeStackResources(logicalNameResourceRequest).getStackResources()) {
                System.out.format("    %1$-40s %2$-25s %3$s\n", resource.getResourceType(), resource.getLogicalResourceId(), resource.getPhysicalResourceId());
            }

        } catch (AmazonServiceException ase){
            System.err.println("Caught an AmazonServiceException, which means your request made it "
                    + "to AWS CloudFormation, but was rejected with an error response for some reason.");
            System.err.println("Error Message:    " + ase.getMessage());
            System.err.println("HTTP Status Code: " + ase.getStatusCode());
            System.err.println("AWS Error Code:   " + ase.getErrorCode());
            System.err.println("Error Type:       " + ase.getErrorType());
            System.err.println("Request ID:       " + ase.getRequestId());
            return false;

        } catch (AmazonClientException ace) {
            System.err.println("Caught an AmazonClientException, which means the client encountered "
                    + "a serious internal problem while trying to communicate with AWS CloudFormation, "
                    + "such as not being able to access the network.");
            System.err.println("Error Message: " + ace.getMessage());
            return false;

        /*} catch (IOException ioe){
            System.err.println("Caught at IOException, problem while reading template file");
            System.err.println("Error Message: " + ioe.getMessage());
            return false;*/

        } catch (InterruptedException iex){
            System.err.println("Error while Thread.sleep() " + iex.getMessage());
            return false;
        }

        return true;
    }

    private String getResourceAsString(String filePath) throws IOException {
        byte[] encoded = Files.readAllBytes(Paths.get(filePath));
        return new String(encoded, StandardCharsets.UTF_8);
    }

    // Wait for a stack to complete transitioning
    // End stack states are:
    //    CREATE_COMPLETE
    //    CREATE_FAILED
    //    DELETE_FAILED
    //    ROLLBACK_FAILED
    // OR the stack no longer exists
    public static String waitForCompletion(AmazonCloudFormation stackbuilder, String stackName) throws InterruptedException {

        DescribeStacksRequest wait = new DescribeStacksRequest();
        wait.setStackName(stackName);
        Boolean completed = false;
        String  stackStatus = "Unknown";
        String  stackReason = "";

        //System.out.println("Waiting..");

        //Map<String, String> statusCache = new HashMap<String, String>();

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

                    /*String name = stack.getStackName() + "/" + stack.getStackId();
                    if(!statusCache.containsKey(name) || statusCache.get(name).compareTo(stack.getStackStatus()) != 0){
                        statusCache.put(name, stack.getStackStatus());
                        System.out.println(stack.getStackName() + " -> " + stack.getStackStatus());
                        System.out.println("\t" + stack.getStackStatusReason());
                    }*/
                    DateTimeFormatter dtf = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss");
                    LocalDateTime now = LocalDateTime.now();
                    System.out.print(dtf.format(now) + " " + stack.getStackName() + " " + stack.getStackStatus());

                    if(stack.getStackStatusReason() != null){
                        System.out.println(" (" + stack.getStackStatusReason() + ")");
                    } else {
                        System.out.println("");
                    }
                }
            }

            // Show we are waiting
            //System.out.print(".");

            // Not done yet so sleep for 10 seconds.
            if (!completed) Thread.sleep(5000);
        }

        // Show we are done
        System.out.print("done\n");

        return stackStatus + " (" + stackReason + ")";
    }
}
