package com.hindsightsoftware.upkeep;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;

import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

import java.io.*;
import java.util.Map;

@Mojo( name = "start", defaultPhase = LifecyclePhase.PRE_INTEGRATION_TEST )
public class Start extends AbstractMojo {

    @Parameter
    private boolean skip = false;

    @Parameter( property = "jira.cloudformation.template.url", defaultValue = "" )
    private String templateUrl;

    @Parameter(property = "jira.cloudformation.conf.file", defaultValue = "${project.build.testOutputDirectory}/cloudformation.conf")
    private String confPath;

    @Parameter( property = "jira.cloudformation.region", defaultValue = "us-west-2" )
    private String regionCode;

    @Parameter( property = "jira.cloudformation.stack.name", defaultValue = "CloudFormation-Stack" )
    private String stackName;

    @Parameter( property = "jira.cloudformation.credentials", defaultValue = "aws.properties" )
    private String credentialsFilePath;

    @Parameter
    private Map<String, String> parameters;

    public void execute() throws MojoExecutionException {
        if(skip)return;

        if(templateUrl.length() == 0){
            throw new MojoExecutionException("Missing cloudformation.template parameter!");
        }

        System.out.println("Writing configuration file to: " + confPath);
        writeBaseUrl(confPath, "http://d541e879.ngrok.io");

        CloudFormation cloudFormation = new CloudFormation(credentialsFilePath);

        if(cloudFormation.build(stackName, templateUrl, regionCode, parameters)){
            System.out.println("Cloud formation successfully created!");
        } else {
            throw new MojoExecutionException("Failed to create cloud formation!");
        }
    }

    private void writeBaseUrl(String path, String url) throws MojoExecutionException {
        try {
            BufferedWriter output = new BufferedWriter(new FileWriter(path));
            output.write("baseUrl=\"" + url + "\"\n");
            output.close();

        } catch (Exception e){
            throw new MojoExecutionException("Error while writing config file: " + e.getMessage());
        }
    }
}
