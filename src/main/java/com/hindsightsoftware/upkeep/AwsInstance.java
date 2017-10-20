package com.hindsightsoftware.upkeep;

import com.amazonaws.AmazonClientException;
import com.amazonaws.AmazonServiceException;
import com.amazonaws.auth.*;
import com.amazonaws.auth.profile.ProfileCredentialsProvider;
import com.amazonaws.services.ec2.AmazonEC2;
import com.amazonaws.services.ec2.AmazonEC2Client;
import com.amazonaws.services.ec2.model.DescribeInstancesRequest;
import com.amazonaws.services.ec2.model.DescribeInstancesResult;
import org.apache.maven.plugin.logging.Log;

public class AwsInstance {
    private final AWSCredentialsProviderChain credentials;
    private final Log log;
    private final AmazonEC2 ec2;

    public AwsInstance(Log log, String credentailsFilePath){
        this.log = log;
        this.credentials = new AWSCredentialsProviderChain(
                new PropertiesFileCredentialsProvider(credentailsFilePath),
                new EnvironmentVariableCredentialsProvider(),
                new SystemPropertiesCredentialsProvider(),
                new ProfileCredentialsProvider(),
                new InstanceProfileCredentialsProvider());
        this.ec2 = new AmazonEC2Client(this.credentials.getCredentials());
    }

    public String getDns(String physicalId){
        try {
            DescribeInstancesRequest request = new DescribeInstancesRequest().withInstanceIds(physicalId);
            DescribeInstancesResult result = ec2.describeInstances(request);
            if(result.getReservations().size() == 0){
                log.error("No reservations instances found for physical ID: " + physicalId);
                return null;
            }
            if(result.getReservations().get(0).getInstances().size() == 0){
                log.error("No instances found for physical ID: " + physicalId);
                return null;
            }
            return result.getReservations().get(0).getInstances().get(0).getPublicDnsName();
        } catch (AmazonServiceException ase){
            AwsUtils.printAmazonServiceException(log, ase);
            return null;

        } catch (AmazonClientException ace) {
            AwsUtils.printAmazonClientException(log, ace);
            return null;
        }
    }
}
