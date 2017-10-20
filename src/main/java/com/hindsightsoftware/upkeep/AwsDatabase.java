package com.hindsightsoftware.upkeep;

import com.amazonaws.AmazonClientException;
import com.amazonaws.AmazonServiceException;
import com.amazonaws.auth.*;
import com.amazonaws.auth.profile.ProfileCredentialsProvider;
import com.amazonaws.services.rds.AmazonRDSClient;
import com.amazonaws.services.rds.model.DescribeDBInstancesRequest;
import com.amazonaws.services.rds.model.DescribeDBInstancesResult;
import org.apache.maven.plugin.logging.Log;

public class AwsDatabase {
    private final AWSCredentialsProviderChain credentials;
    private final Log log;
    private final AmazonRDSClient rds;

    public AwsDatabase(Log log, String credentailsFilePath){
        this.log = log;
        this.credentials = new AWSCredentialsProviderChain(
                new PropertiesFileCredentialsProvider(credentailsFilePath),
                new EnvironmentVariableCredentialsProvider(),
                new SystemPropertiesCredentialsProvider(),
                new ProfileCredentialsProvider(),
                new InstanceProfileCredentialsProvider());
        this.rds = new AmazonRDSClient(this.credentials.getCredentials());
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
