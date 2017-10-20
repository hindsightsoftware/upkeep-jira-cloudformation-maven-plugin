package com.hindsightsoftware.upkeep;

import org.apache.maven.plugin.logging.Log;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

public class JiraRestoreUtils {
    public static boolean uploadCredentials(SecuredShellClient ssh, String s3AwsCredentials, String s3AwsConfig){
        List<SecuredShellClient.FilePair> files = Arrays.asList(
                new SecuredShellClient.FilePair(s3AwsCredentials, ".aws/"),
                new SecuredShellClient.FilePair(s3AwsConfig, ".aws/")
        );

        return ssh.execute("mkdir -p ~/.aws") == 0 && ssh.uploadFile(files);
    }

    public static boolean getPsqlFromBucket(SecuredShellClient ssh, String bucketName, String psqlFileName){
        return ssh.execute("aws s3 cp s3://" + bucketName + "/" + psqlFileName + " .") == 0;
    }

    public static boolean stopJira(SecuredShellClient ssh){
        List<String> commands = Arrays.asList(
                // Stop
                "(sudo /opt/atlassian/jira/bin/shutdown.sh > /dev/null 2>&1 || true)",

                // Wait until is down
                "while pgrep -u root java > /dev/null; do echo \'Waiting for JIRA to shutdown...\' & sleep 10 & (sudo /opt/atlassian/jira/bin/shutdown.sh > /dev/null 2>&1 || true); done; echo \'JIRA is down.\'"
        );

        return ssh.execute(commands) == 0;
    }

    public static boolean startJira(SecuredShellClient ssh){
        List<String> commands = Arrays.asList(
                // Sometimes JIRA complains about this...
                //"sudo rm -f /opt/atlassian/jira/work/catalina.pid",

                // Start
                //"sudo su nohup /opt/atlassian/jira/bin/catalina.sh start",
                "sudo su -c \"exec env USE_NOHUP=true /opt/atlassian/jira/bin/startup.sh > /dev/null\"; sleep 10",

                // Check if PID is alive
                "ps cax | grep $(sudo cat /opt/atlassian/jira/work/catalina.pid) || (echo \"No active JIRA PID found! JIRA did not start!\" & exit 1)"
        );
        return ssh.execute(commands) == 0;
    }

    public static boolean restoreFromPsql(SecuredShellClient ssh, String endpoint, String password, String psqlFileName){
        List<String> commands = Arrays.asList(
                "PGPASSWORD=\'" + password + "\' dropdb -h " + endpoint + " --if-exists -p 5432 -U postgres jira",

                "PGPASSWORD=\'" + password + "\' createdb -h " + endpoint + " -p 5432 -U postgres jira",

                "PGPASSWORD=\'" + password + "\' pg_restore -n public -i -h " + endpoint + " -p 5432 -U postgres -d jira \"" + psqlFileName + "\""
        );

        return ssh.execute(commands) == 0;
    }

    public static boolean getIndexesFromBucket(SecuredShellClient ssh, String bucketName, String indexesFileName){
        List<String> commands = Arrays.asList(
                // Download xml/zip file from S3 bucket
                "aws s3 cp s3://" + bucketName + "/" + indexesFileName + " .",

                // Copy to destination
                "sudo tar -xzvf " + indexesFileName + " -C /var/atlassian/application-data/jira/caches/indexes > /dev/null",

                // Make jira as owner of the extracted indexes
                "sudo chown -R jira /var/atlassian/application-data/jira/caches/indexes/"
        );

        return ssh.execute(commands) == 0;
    }

    private static boolean checkResponse(Log log, Http.Response response) throws IOException {
        if(response.getStatusCode() == 302){
            return true;
        }

        String body = response.getBody();
        if(body.contains("Could not find file at this location")){
            log.error("Unable to restore JIRA, file not found");
        }
        else if(body.contains("JIRA has already been set up")){
            log.error("JIRA has already been set up");
        }
        else {
            log.error("Incorrect response code returned: " + response.getStatusCode() + " but expected 302");
            log.error("Has your JIRA already been set up?");
        }

        return false;
    }

    public static boolean waitForJiraToBeAlive(SecuredShellClient ssh, Log log, String baseUrl, int maxWaitTime){
        return new TimeoutBlock(maxWaitTime, 10000) {@Override public boolean block(){
            return ssh.execute("curl -sS --fail --connect-timeout 5 --max-time 5 -o /dev/null localhost:8080") == 0;
        }}.run();
    }

    public static boolean waitForUrlToBeAlive(Log log, String baseUrl, int maxWaitTime){
        return new TimeoutBlock(maxWaitTime, 10000) {@Override public boolean block() {
            String host = "http://" + baseUrl + "/";
            try {
                Http.Response response = Http.GET(host).timeout(5).send();
                log.info("GET \"" + host + "\" returned " + response.getStatusCode());
                return response.getStatusCode() >= 200 && response.getStatusCode() < 500;
            } catch (IOException e) {
                log.info("GET \"" + host + "\" timeout");
                return false;
            }
        }}.run();
    }
}