package com.hindsightsoftware.upkeep;

import com.amazonaws.AmazonClientException;
import com.amazonaws.AmazonServiceException;
import com.amazonaws.services.rds.AmazonRDS;
import com.amazonaws.services.rds.AmazonRDSClientBuilder;
import com.amazonaws.services.rds.model.DescribeDBInstancesRequest;
import com.amazonaws.services.rds.model.DescribeDBInstancesResult;
import com.amazonaws.auth.profile.ProfileCredentialsProvider;
import com.amazonaws.auth.AWSStaticCredentialsProvider;
import org.apache.maven.plugin.logging.Log;

public class AwsDatabase {
    private final Log log;
    private final AmazonRDS rds;

    public AwsDatabase(Log log){
        this.log = log;
        AmazonRDSClientBuilder builder = AmazonRDSClientBuilder.standard();
        builder.withCredentials(
                new AWSStaticCredentialsProvider(new ProfileCredentialsProvider("default").getCredentials()));
        builder.setRegion("us-east-2");
        this.rds = builder.build();
    }

    public String getEndpoint(String physicalId){
        try {
            DescribeDBInstancesRequest request = new DescribeDBInstancesRequest().withDBInstanceIdentifier(physicalId);
            DescribeDBInstancesResult result = rds.describeDBInstances(request);
            if(result.getDBInstances().size() == 0){
                log.error("No database instances found for physical ID: " + physicalId);
                return null;
            }
            return result.getDBInstances().get(0).getEndpoint().getAddress();
        } catch (AmazonServiceException ase){
            AwsUtils.printAmazonServiceException(log, ase);
            return null;

        } catch (AmazonClientException ace) {
            AwsUtils.printAmazonClientException(log, ace);
            return null;
        }
    }
}
