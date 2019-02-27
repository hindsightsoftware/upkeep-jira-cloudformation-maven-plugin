package com.hindsightsoftware.upkeep;

import com.amazonaws.AmazonClientException;
import com.amazonaws.AmazonServiceException;
import com.amazonaws.services.ec2.AmazonEC2;
import com.amazonaws.services.ec2.AmazonEC2ClientBuilder;
import com.amazonaws.services.ec2.model.DescribeInstancesRequest;
import com.amazonaws.services.ec2.model.DescribeInstancesResult;
import com.amazonaws.auth.profile.ProfileCredentialsProvider;
import com.amazonaws.auth.AWSStaticCredentialsProvider;
import org.apache.maven.plugin.logging.Log;

public class AwsInstance {
    private final Log log;
    private final AmazonEC2 ec2;

    public AwsInstance(Log log){
        this.log = log;
        AmazonEC2ClientBuilder builder = AmazonEC2ClientBuilder.standard();
        builder.withCredentials(
                new AWSStaticCredentialsProvider(new ProfileCredentialsProvider("default").getCredentials()));
        builder.setRegion("us-east-2");
        this.ec2 = builder.build();
    }

    public String getPrivateIp(String physicalId){
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
            return result.getReservations().get(0).getInstances().get(0).getPrivateIpAddress();
        } catch (AmazonServiceException ase){
            AwsUtils.printAmazonServiceException(log, ase);
            return null;

        } catch (AmazonClientException ace) {
            AwsUtils.printAmazonClientException(log, ace);
            return null;
        }
    }
}
