package com.hindsightsoftware.upkeep;

import com.amazonaws.AmazonClientException;
import com.amazonaws.AmazonServiceException;
import com.amazonaws.services.ec2.AmazonEC2;
import com.amazonaws.services.ec2.AmazonEC2ClientBuilder;
import com.amazonaws.services.ec2.model.DescribeInstanceStatusRequest;
import com.amazonaws.services.ec2.model.DescribeInstanceStatusResult;
import com.amazonaws.services.ec2.model.InstanceStatus;
import com.amazonaws.auth.profile.ProfileCredentialsProvider;
import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.services.elasticloadbalancing.AmazonElasticLoadBalancing;
import com.amazonaws.services.elasticloadbalancing.AmazonElasticLoadBalancingClientBuilder;
import com.amazonaws.services.elasticloadbalancing.model.*;

import org.apache.maven.plugin.logging.Log;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class AwsLoadBalancer {
    private final Log log;
    private final AmazonElasticLoadBalancing elb;
    private final AmazonEC2 ec2;

    public AwsLoadBalancer(Log log){
        this.log = log;
        AmazonElasticLoadBalancingClientBuilder builder = AmazonElasticLoadBalancingClientBuilder.standard();
        builder.withCredentials(
                new AWSStaticCredentialsProvider(new ProfileCredentialsProvider("default").getCredentials()));
        builder.setRegion("us-east-2");
        this.elb = builder.build();
        
        AmazonEC2ClientBuilder builder2 = AmazonEC2ClientBuilder.standard();
        builder2.withCredentials(
                new AWSStaticCredentialsProvider(new ProfileCredentialsProvider("default").getCredentials()));
        builder2.setRegion("us-east-2");
        this.ec2 = builder2.build();
    }

    public Map<String, String> getHealthStatus(String physicalId){
        try {
            DescribeInstanceHealthRequest request = new DescribeInstanceHealthRequest().withLoadBalancerName(physicalId);
            DescribeInstanceHealthResult result = elb.describeInstanceHealth(request);
            return result.getInstanceStates().stream().collect(Collectors.toMap(InstanceState::getInstanceId, InstanceState::getState));
        } catch (AmazonServiceException ase){
            AwsUtils.printAmazonServiceException(log, ase);
            return null;

        } catch (AmazonClientException ace) {
            AwsUtils.printAmazonClientException(log, ace);
            return null;

        }
    }

    public String getDns(String physicalId){
        try {
            DescribeLoadBalancersRequest request = new DescribeLoadBalancersRequest().withLoadBalancerNames(physicalId);
            DescribeLoadBalancersResult result = elb.describeLoadBalancers(request);

            if (result.getLoadBalancerDescriptions().size() == 0) {
                log.error("Cloud not find JIRA load balancer!");
                return null;
            }

            return result.getLoadBalancerDescriptions().get(0).getDNSName();
        } catch (AmazonServiceException ase){
            AwsUtils.printAmazonServiceException(log, ase);
            return null;

        } catch (AmazonClientException ace) {
            AwsUtils.printAmazonClientException(log, ace);
            return null;

        }
    }

    public List<String> getInstanceIDs(String physicalId){
        try {
            DescribeLoadBalancersRequest request = new DescribeLoadBalancersRequest().withLoadBalancerNames(physicalId);
            List<LoadBalancerDescription> loadBalancers = elb.describeLoadBalancers(request).getLoadBalancerDescriptions();

            if (loadBalancers.size() != 1) {
                log.error("Cloud not find JIRA load balancer!");
                return null;
            }

            return loadBalancers.get(0).getInstances().stream().map(Instance::getInstanceId).collect(Collectors.toList());
        } catch (AmazonServiceException ase){
            AwsUtils.printAmazonServiceException(log, ase);
            return null;

        } catch (AmazonClientException ace) {
            AwsUtils.printAmazonClientException(log, ace);
            return null;

        }
    }

    public boolean waitForInstances(String physicalId){
        try {
            DescribeLoadBalancersRequest request = new DescribeLoadBalancersRequest().withLoadBalancerNames(physicalId);
            List<LoadBalancerDescription> loadBalancers = elb.describeLoadBalancers(request).getLoadBalancerDescriptions();

            if(loadBalancers.size() != 1){
                log.error("Cloud not find JIRA load balancer!");
                return false;
            }

            List<com.amazonaws.services.elasticloadbalancing.model.Instance> loadBalancerInstances = loadBalancers.get(0).getInstances();
            if(loadBalancerInstances.size() == 0){
                log.error("No instances found for JIRA load balancer!");
                return false;
            }

            // Wait for all instances to start...
            for(Instance instance : loadBalancerInstances) {
                String instanceId = instance.getInstanceId();
                log.info("Found JIRA Node instance: \"" + instanceId + "\"");
                log.info("Waiting for instance to be ready...");

                do {
                    DescribeInstanceStatusRequest describeInstanceRequest = new DescribeInstanceStatusRequest().withInstanceIds(instanceId);
                    DescribeInstanceStatusResult describeInstancesResult = ec2.describeInstanceStatus(describeInstanceRequest);

                    if (describeInstancesResult.getInstanceStatuses().size() == 0) {
                        log.error("No reservation found for instance ID: \"" + instanceId + "\"");
                        return false;
                    }

                    InstanceStatus instanceStatus = describeInstancesResult.getInstanceStatuses().get(0);

                    String state = instanceStatus.getInstanceState().getName();
                    String status = instanceStatus.getInstanceStatus().getStatus();

                    if (state.equals("terminated")) {
                        log.error("JIRA Node is terminated!");
                    }
                    if (state.equals("running") && status.equals("ok")) {
                        log.info("JIRA Node instance: \"" + instanceId + "\" is running and ready for connection");
                        break;
                    } else {
                        log.info("JIRA Node instance: \"" + instanceId + "\" status: \"" + status + "\" waiting...");
                        Thread.sleep(30000);
                    }

                } while (true);
            }

            return true;

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
    }

    public boolean waitForHealthCheck(String physicalId, int maxWaitTime, long waitDelay){
        return new TimeoutBlock(maxWaitTime, 10000) {@Override public boolean block(){
            Map<String, String> statuses = getHealthStatus(physicalId);

            long num = statuses.entrySet().stream().filter(s -> s.getValue().equals("OutOfService")).count();
            if(num == 0){
                log.info("All instances are in service and ready!");
                return true;
            }

            log.info("Waiting for " + num + " load balancer instances health check out of " + statuses.size() + ":");
            for(Map.Entry<String, String> entry : statuses.entrySet()){
                log.info("    " + entry.getKey() + " - " + entry.getValue());
            }

            try {
                log.info("Checking again in 15 seconds...");
                Thread.sleep(waitDelay);
            } catch (InterruptedException e){
                log.error("InterruptedException: " + e.getMessage());
            }

            return false;
        }}.run();
    }
}
