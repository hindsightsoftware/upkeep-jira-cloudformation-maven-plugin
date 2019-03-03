package com.hindsightsoftware.upkeep;

import com.jcraft.jsch.JSchException;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;

import org.apache.maven.plugin.logging.Log;
import org.apache.maven.plugin.logging.SystemStreamLog;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

import java.io.*;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Mojo( name = "start", defaultPhase = LifecyclePhase.PRE_INTEGRATION_TEST )
public class Start extends AbstractMojo {

    @Parameter
    private boolean skip = false;

    @Parameter( property = "jira.cloudformation.template.url", defaultValue = "https://aws-quickstart.s3.amazonaws.com/quickstart-atlassian-jira/templates/quickstart-jira-dc-with-vpc.template.yaml" )
    private String templateUrl;

    @Parameter(property = "jira.cloudformation.conf.file", defaultValue = "${project.build.testOutputDirectory}/cloudformation.conf")
    private String confPath;

    @Parameter( property = "jira.cloudformation.stack.name", defaultValue = "JIRA-Data-Center" )
    private String stackName;

    @Parameter( property = "jira.cloudformation.onfailure", defaultValue = "DELETE" )
    private String onFailure;

    @Parameter( property = "jira.cloudformation.base.url.id", defaultValue = "JIRAURL" )
    private String baseUrlOutputsId;

    @Parameter( property = "jira.cloudformation.base.url.path", defaultValue = "${project.build.testOutputDirectory}/baseurl" )
    private String baseUrlPath;

    @Parameter
    private List<File> uploads;

    @Parameter
    private List<String> commands;

    @Parameter( property = "jira.cloudformation.rds.password", defaultValue = "" )
    private String rdsPassword;

    @Parameter
    private Map<String, String> parameters;

    @Parameter
    private String[] exports;

    @Parameter( property = "jira.cloudformation.ssh.private.key", defaultValue = "" )
    private File sshPrivateKeyFile;

    @Parameter( property = "jira.cloudformation.s3.aws.credentails", defaultValue = "" )
    private File s3AwsCredentials;

    @Parameter( property = "jira.cloudformation.s3.aws.config", defaultValue = "" )
    private File s3AwsConfig;

    @Parameter( property = "jira.cloudformation.s3.restore.indexes.enabled", defaultValue = "true" )
    private boolean s3RestoreIndexesEnabled;

    @Parameter(property = "jira.cloudformation.s3.restore.database.enabled", defaultValue = "true")
    private boolean s3RestoreDatabaseEnabled;

    @Parameter( property = "jira.cloudformation.s3.restore.bucket", defaultValue = "" )
    private String s3RestoreBucket;

    @Parameter( property = "jira.cloudformation.s3.restore.psql", defaultValue = "" )
    private String s3RestorePsqlFileName;

    @Parameter( property = "jira.cloudformation.s3.restore.indexes", defaultValue = "" )
    private String s3RestoreIndexesFileName;

    @Parameter( property = "jira.cloudformation.max.wait.jira", defaultValue = "300")
    private Integer maxJiraHttpWait;

    @Parameter( property = "jira.cloudformation.max.wait.load", defaultValue = "300")
    private Integer maxLoadBalancerWait;

    @Parameter( property = "jira.cloudformation.setenv", defaultValue = "" )
    private File setenvFile;

    private Log log;

    public void setLog(Log log){
        this.log = new SystemStreamLog();
    }

    public void execute() throws MojoExecutionException {
        if(skip)return;

        if(templateUrl.length() == 0){
            throw new MojoExecutionException("Missing cloudformation.template parameter!");
        }

        AwsCloudFormation cloudFormationClient = new AwsCloudFormation(log);
        AwsDatabase databaseClient = new AwsDatabase(log);
        AwsInstance instanceClient = new AwsInstance(log);
        AwsLoadBalancer loadBalancerClient = new AwsLoadBalancer(log);

        Map<String, String> outputs = new HashMap<String, String>();
        Map<String, String> resources = new HashMap<String, String>();

        // Build JIRA stack and save all outputs and resources generated
        if(cloudFormationClient.build(stackName, templateUrl, onFailure, parameters, outputs, resources)){
            log.info("Cloud formation successfully created!");
        } else {
            throw new MojoExecutionException("Failed to create cloud formation!");
        }

        // Write outputs to configuration file based on a filter provided by user
        if(exports != null && exports.length > 0) {
            final List<String> exportsList = Arrays.asList(exports);
            log.info("Writing configuration file to: " + confPath);
            writeOutputs(confPath, outputs.entrySet().stream().filter(p -> exportsList.contains(p.getKey()))
                    .collect(Collectors.toMap(p -> p.getKey(), p -> (p.getValue() != null ? p.getValue() : "")))
            );
        } else {
            log.error("Configuration file specified: " + confPath + " but no export keys were defined! Exporting everything...");
            writeOutputs(confPath, outputs);
        }

        // Get bastion IP
        String bastionIp = cloudFormationClient.getOutputValue(stackName, "BastionIP");
        log.info("Got bastion IP: " + bastionIp);

        // Get Jira base URL
        String baseUrl = cloudFormationClient.getOutputValue(stackName, "LoadBalancerURL");
        log.info("Got Jira base URL: " + baseUrl);

        // Get database URL
        String databaseEndpointUrl = cloudFormationClient.getOutputValue(stackName, "DBEndpointAddress");
        log.info("Got Jira database URL: " + databaseEndpointUrl);

        // Get the Jira Stack
        String jiraStackPhysicalId = cloudFormationClient.getResourceValue(stackName, "JiraDCStack");
        log.info("Got Jira stack ID: " + jiraStackPhysicalId);

        // Get load balancer physical ID
        String loadBalancerPhysicalId = cloudFormationClient.getResourceValue(jiraStackPhysicalId, "LoadBalancer");
        log.info("Got Jira load balancer ID: " + loadBalancerPhysicalId);

        // Wait for all JIRA Nodes
        if (!loadBalancerClient.waitForInstances(loadBalancerPhysicalId)) {
            throw new MojoExecutionException("Something went wrong while waiting for instances");
        }

        // Get all instances private IPs
        List<String> instancesIps = loadBalancerClient.getInstanceIDs(loadBalancerPhysicalId).stream()
                .map(id -> instanceClient.getPrivateIp(id)).collect(Collectors.toList());

        for (String ip : instancesIps) {
            log.info("Found instance private IP: " + ip);
        }

        if (s3RestoreIndexesEnabled || s3RestoreDatabaseEnabled) {
            restoreFromPsqlBackup(bastionIp, instancesIps, databaseEndpointUrl);
        } else {
            log.info("No backup restore mechanism specified... skipping...");
        }

        // Wait for health check
        log.info(
                "Waiting to health check of all JIRA instances. This is needed in order for the load balancer to wake up!");
        if (!loadBalancerClient.waitForHealthCheck(loadBalancerPhysicalId, maxLoadBalancerWait, 15000)) {
            throw new MojoExecutionException("Health Check failed!");
        }

        // Wait for JIRA to return http code between 200 - 499
        log.info("Waiting for JIRA on load balancer URL!");
        if (!JiraRestoreUtils.waitForUrlToBeAlive(log, baseUrl, maxJiraHttpWait)) {
            throw new MojoExecutionException("Something went wrong while waiting for JIRA");
        }

        if (!isEmpty(baseUrlPath)) {
            writeBaseUrl(baseUrlPath, baseUrl);
        }
    }

    private void restoreFromPsqlBackup(String bastionIp, List<String> ec2PrivateIpAddresses, String rdsInstanceEndpoint) throws MojoExecutionException {
        log.info("Restoring JIRA for: " + ec2PrivateIpAddresses.size() + " EC2 instance nodes");

        log.info("Stopping all instances of JIRA in order to restore the database...");
        for(String address : ec2PrivateIpAddresses) {
            // Open ssh connection
            SecuredShellClient ssh = getSsh(bastionIp, address);
            if (!JiraRestoreUtils.stopJira(ssh)) {
                throw new MojoExecutionException("Failed to stop JIRA in instance: " + address);
            }
        }

        boolean psqlRestored = !s3RestoreDatabaseEnabled;

        for(String address : ec2PrivateIpAddresses) {
            // Open ssh connection
            SecuredShellClient ssh = getSsh(bastionIp, address);

            // Restore JIRA from Postgres SQL

            // upload aws credentials needed to access S3 bucket
            if (!JiraRestoreUtils.uploadCredentials(ssh, s3AwsCredentials.getAbsolutePath(), s3AwsConfig.getAbsolutePath())) {
                throw new MojoExecutionException("Failed to upload aws credentials for accessing S3 bucket!");
            }

            // Restoring Postgres SQL must be done only once.
            // However, indexes need to be restored on all instances.
            if(!psqlRestored) {
                psqlRestored = true;

                // download the psql file
                if (!JiraRestoreUtils.getPsqlFromBucket(ssh, s3RestoreBucket, s3RestorePsqlFileName)) {
                    throw new MojoExecutionException("Failed to get Postgres SQL backup from S3 bucket!");
                }

                // restore Postgres SQL
                if (!JiraRestoreUtils.restoreFromPsql(log, ssh, rdsInstanceEndpoint, rdsPassword, s3RestorePsqlFileName)) {
                    throw new MojoExecutionException("Failed restore Postgres SQL backup!");
                }
            }
            
            if (s3RestoreIndexesEnabled) {
                // download the indexes file and restore it
                if (!JiraRestoreUtils.getIndexesFromBucket(ssh, s3RestoreBucket, s3RestoreIndexesFileName)) {
                    throw new MojoExecutionException("Failed to get indexes backup from S3 bucket!");
                }
            }

            // start JIRA again
            if (!JiraRestoreUtils.startJira(ssh)) {
                // Try starting it second time
                if(!JiraRestoreUtils.startJira(ssh)) {
                    throw new MojoExecutionException("Failed to start JIRA in instance: " + address);
                }
            }
        }
    }

    private SecuredShellClient getSsh(String bastionIp, String host) throws MojoExecutionException{
        log.info("Connecting to " + host + " via bastion " + bastionIp + "...");
        try {
            return new SecuredShellClient(log, bastionIp, host, "ec2-user", sshPrivateKeyFile);
        } catch (JSchException e) {
            throw new MojoExecutionException("SSH error: " + e.getMessage());
        }
    }

    private void writeOutputs(String path, Map<String, String> params) throws MojoExecutionException {
        try {
            BufferedWriter output = new BufferedWriter(new FileWriter(path));
            for(Map.Entry<String, String> param : params.entrySet()){
                output.write(param.getKey() + "=\"" + param.getValue() + "\"\n");
            }
            output.close();

        } catch (Exception e){
            throw new MojoExecutionException("Error while writing config file: " + e.getMessage());
        }
    }

    private void writeBaseUrl(String path, String url) throws MojoExecutionException{
        try {
            BufferedWriter output = new BufferedWriter(new FileWriter(path));
            output.write(url);
            output.close();

        } catch (Exception e){
            throw new MojoExecutionException("Error while writing config file: " + e.getMessage());
        }
    }

    private void writeInstanceUrls(String path, List<String> instances) throws MojoExecutionException{
        try {
            BufferedWriter output = new BufferedWriter(new FileWriter(path));
            for(String address : instances){
                output.write(address);
                output.write("\n");
            }
            output.close();

        } catch (Exception e){
            throw new MojoExecutionException("Error while writing ec2 instances file: " + e.getMessage());
        }
    }

    // Sanity check
    private boolean isEmpty(String str){
        return str == null || str.length() == 0;
    }
}
