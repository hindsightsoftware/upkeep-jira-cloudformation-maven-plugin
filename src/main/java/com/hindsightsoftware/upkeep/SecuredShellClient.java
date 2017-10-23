package com.hindsightsoftware.upkeep;

import java.io.*;
import java.util.List;


import com.jcraft.jsch.*;
import org.apache.maven.plugin.logging.Log;

public class SecuredShellClient {
    public static class FilePair {
        public final String src;
        public final String dst;
        public FilePair(String src, String dst){
            this.src = src;
            this.dst = dst;
        }
    }

    private final Log log;
    private final JSch jsch;
    private final String host;
    private final String user;
    private final int port = 22;

    public SecuredShellClient(Log log, String host, String user, File keypairFilePath) throws JSchException {
        this.log = log;
        this.jsch = new JSch();
        this.host = host;
        this.user = user;
        this.jsch.addIdentity(keypairFilePath.getAbsolutePath());
    }

    public boolean uploadFile(List<FilePair> files) {
        log.info("Uploading: " + files.size() + " files...");
        try {
            Session session = jsch.getSession(user, host, port);
            java.util.Properties config = new java.util.Properties();
            config.put("StrictHostKeyChecking", "no");
            session.setConfig(config);
            session.connect();

            try {
                for(FilePair pair : files){
                    log.info("Uploading: " + pair.src + " -> " + pair.dst);
                    Channel channel = session.openChannel("sftp");
                    channel.connect();
                    ChannelSftp channelSftp = (ChannelSftp) channel;
                    channelSftp.cd(pair.dst);

                    File f1 = new File(pair.src);
                    channelSftp.put(new FileInputStream(f1), f1.getName(), ChannelSftp.OVERWRITE);

                    channelSftp.disconnect();
                }
            } finally {
                session.disconnect();
            }

            return true;
        } catch (FileNotFoundException e){
            log.error("Error reading source file: " + e.getMessage());
            e.printStackTrace();
            return false;
        } catch (SftpException e){
            log.error("Error while uploading files: " + e.getMessage());
            e.printStackTrace();
            return false;
        } catch (JSchException e){
            log.error("Error while connecting: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    public int execute(List<String> commands){
        for(String command : commands){
            int ret = execute(command);
            if(ret != 0)return ret;
        }
        return 0;
    }

    public int execute(String command){
        try {
            Session session = jsch.getSession(user, host, port);
            java.util.Properties config = new java.util.Properties();
            config.put("StrictHostKeyChecking", "no");
            session.setConfig(config);
            session.connect();

            try {
                log.info("SSH exec: " + command);
                ChannelExec channelExec = (ChannelExec)session.openChannel("exec");
                channelExec.setCommand(command);
                channelExec.setInputStream(null);
                channelExec.setErrStream(System.err);

                InputStream stdout = channelExec.getInputStream();
                InputStream stderr = channelExec.getErrStream();
                channelExec.connect();

                try {
                    InputStreamReader stdoutReader = new InputStreamReader(stdout);
                    BufferedReader stdoutBufferedReader = new BufferedReader(stdoutReader);
                    InputStreamReader stderrReader = new InputStreamReader(stderr);
                    BufferedReader stderrBufferedReader = new BufferedReader(stderrReader);

                    String line;

                    while ((line = stderrBufferedReader.readLine()) != null) {
                        log.info(line);
                    }

                    while ((line = stdoutBufferedReader.readLine()) != null) {
                        log.info(line);
                    }

                    stderrBufferedReader.close();
                    stderrReader.close();
                    stdoutBufferedReader.close();
                    stdoutReader.close();

                    int status = channelExec.getExitStatus();
                    log.info("Command returned status: " + status);
                    return status;
                } finally {
                    channelExec.disconnect();
                }
            } finally {
                session.disconnect();
            }

        } catch (JSchException e){
            log.error("Error while connecting: " + e.getMessage());
            e.printStackTrace();
            return -1;
        } catch (IOException e){
            log.error("Error while reading input stream: " + e.getMessage());
            e.printStackTrace();
            return -1;
        }
    }
}
