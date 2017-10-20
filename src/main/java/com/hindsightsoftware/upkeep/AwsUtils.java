package com.hindsightsoftware.upkeep;

import com.amazonaws.AmazonClientException;
import com.amazonaws.AmazonServiceException;
import org.apache.maven.plugin.logging.Log;

public class AwsUtils {
    public static void printAmazonServiceException(Log log, AmazonServiceException ase){
        log.error("Caught an AmazonServiceException, which means your request made it "
                + "to AWS AwsCloudFormation, but was rejected with an error response for some reason.");
        log.error("Error Message:    " + ase.getMessage());
        log.error("HTTP Status Code: " + ase.getStatusCode());
        log.error("AWS Error Code:   " + ase.getErrorCode());
        log.error("Error Type:       " + ase.getErrorType());
        log.error("Request ID:       " + ase.getRequestId());
    }
    public static void printAmazonClientException(Log log, AmazonClientException ace){
        log.error("Caught an AmazonClientException, which means the client encountered "
                + "a serious internal problem while trying to communicate with AWS AwsCloudFormation, "
                + "such as not being able to access the network.");
        log.error("Error Message: " + ace.getMessage());
    }
}
