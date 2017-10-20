package com.hindsightsoftware.upkeep;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.plugin.logging.SystemStreamLog;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

@Mojo( name = "stop", defaultPhase = LifecyclePhase.POST_INTEGRATION_TEST )
public class Stop extends AbstractMojo {
    @Parameter
    private boolean skip = false;

    @Parameter( property = "jira.cloudformation.stack.name", defaultValue = "JIRA-Data-Center" )
    private String stackName;

    @Parameter( property = "jira.cloudformation.credentials", defaultValue = "aws.properties" )
    private String credentialsFilePath;

    private Log log;

    public void setLog(Log log){
        this.log = new SystemStreamLog();
    }

    public void execute() throws MojoExecutionException {
        if(skip)return;

        AwsCloudFormation cloudFormationClient = new AwsCloudFormation(log, credentialsFilePath);

        if(!cloudFormationClient.stop(stackName)){
            throw new MojoExecutionException("Failed to stop stack!");
        }
    }
}
