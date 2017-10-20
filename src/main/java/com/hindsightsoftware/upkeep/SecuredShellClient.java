package com.hindsightsoftware.upkeep;

import java.io.*;
import java.util.List;

import net.schmizz.sshj.DefaultConfig;
import net.schmizz.sshj.SSHClient;
import net.schmizz.sshj.connection.channel.direct.Session;
import net.schmizz.sshj.connection.channel.direct.Session.Command;
import net.schmizz.sshj.transport.verification.PromiscuousVerifier;
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
    private SSHClient ssh;

    public SecuredShellClient(Log log, String address, String sshuser, File keypairFilePath) throws IOException {
        this.log = log;
        this.ssh = beginClient(address, sshuser, keypairFilePath.getAbsolutePath());
    }

    public boolean uploadFile(List<FilePair> files) {
        try {
            Session session = ssh.startSession();
            session.allocateDefaultPTY();

            try {
                for(FilePair pair : files){
                    log.info("Uploading: " + pair.src + " -> " + pair.dst);
                    ssh.newSCPFileTransfer().upload(pair.src, pair.dst);
                }
            } finally {
                session.close();
            }

            return true;
        } catch (IOException e){
            log.error("Error while uploading files: " + e.getMessage());
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
            Session session = ssh.startSession();
            session.allocateDefaultPTY();

            try {
                Command cmd = session.exec(command);
                InputStream stdout = cmd.getInputStream();
                InputStream stderr = cmd.getErrorStream();

                Reader stdoutReader = new InputStreamReader(stdout, "UTF-8");
                Reader stderrReader = new InputStreamReader(stderr, "UTF-8");

                StringBuilder stdoutBuilder = new StringBuilder();
                StringBuilder stderrBuilder = new StringBuilder();

                char buffer[] = new char[1024];

                while(cmd.isOpen() || stdout.available() > 0 || stderr.available() > 0){
                    while(stdout.available() > 0){
                        int len = stdoutReader.read(buffer, 0, 1024);
                        stdoutBuilder.append(buffer, 0, len);

                        int index = stdoutBuilder.indexOf("\n");
                        if(index > 0){
                            log.info(stdoutBuilder.substring(0, index));
                            stdoutBuilder = new StringBuilder(stdoutBuilder.substring(index+1, stdoutBuilder.length()));
                        }
                    }

                    while(stderr.available() > 0){
                        int len = stderrReader.read(buffer, 0, 1024);
                        stderrBuilder.append(buffer, 0, len);

                        int index = stderrBuilder.indexOf("\n");
                        if(index > 0){
                            log.error(stderrBuilder.substring(0, index));
                            stderrBuilder = new StringBuilder(stderrBuilder.substring(index+1, stderrBuilder.length()));
                        }
                    }
                }

                if(stdoutBuilder.length() > 0){
                    log.info(stdoutBuilder.toString());
                }

                if(stderrBuilder.length() > 0){
                    log.error(stderrBuilder.toString());
                }

                cmd.join();
                int status = cmd.getExitStatus();
                session.close();
                return status;

            } finally {
                session.close();
            }
        } catch (IOException e){
            log.error(e);
            return -1;
        }
    }

    public void close(){ // noexcept
        try {
            ssh.disconnect();
        } catch (IOException e){
            log.error(e);
        }
    }

    private SSHClient beginClient(String address, String sshuser, String keypairFilePath) throws IOException {
        SSHClient ssh = new SSHClient(new DefaultConfig());
        ssh.addHostKeyVerifier(new PromiscuousVerifier());
        ssh.connect(address);
        ssh.authPublickey(sshuser, keypairFilePath);
        ssh.useCompression();
        return ssh;
    }
}
