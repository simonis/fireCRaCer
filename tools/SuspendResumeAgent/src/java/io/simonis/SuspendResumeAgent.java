package io.simonis;

import java.lang.instrument.Instrumentation;
import java.net.ServerSocket;
import java.net.Socket;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.IOException;
import java.io.PrintWriter;

import io.simonis.crac.Core;
import io.simonis.crac.CheckpointException;
import io.simonis.crac.RestoreException;

public class SuspendResumeAgent implements Runnable {

  static void loadNativeLibrary() {
    try {
      File tmp = File.createTempFile("lib", ".so");
      tmp.deleteOnExit();
      FileOutputStream fos = new FileOutputStream(tmp);
      InputStream so = SuspendResumeAgent.class.getResourceAsStream("/libSuspendResumeAgent.so");
      so.transferTo(fos);
      System.load(tmp.getCanonicalPath());
    } catch (Exception e) {
      System.err.println("Can't load native library");
      e.printStackTrace();
    }
  }

  static {
    // System.out.println(SuspendResumeAgent.class.getResource("/libSuspendResumeAgent.so"));
    loadNativeLibrary();
  }
  public static native int suspendThreads();
  public static native int resumeThreads();
  public static native int forceGC();

  static final int port = Integer.getInteger("io.simonis.SuspendResumeAgent.port", 1234);

  public void run() {
    ServerSocket serverSocket;
    try {
      serverSocket = new ServerSocket(port, 1);
    } catch (IOException ioe) {
      System.err.println("Can't create server socket on port " + port);
      ioe.printStackTrace();
      return;
    }
    while (true) {
      try (Socket client = serverSocket.accept();
           PrintWriter out = new PrintWriter(client.getOutputStream(), true);
           BufferedReader in = new BufferedReader(new InputStreamReader(client.getInputStream()))) {
        String line;
        while ((line = in.readLine()) != null) {
          out.println(line);
          if ("SUSPEND".equals(line)) {
            try {
              Core.getGlobalContext().beforeCheckpoint(null);
            } catch (CheckpointException ce) {
              System.err.println("Error when calling beforeCheckpoint()");
              ce.printStackTrace();
            }
            forceGC();
            suspendThreads();
            client.close();
            break;
          } else if ("RESUME".equals(line)) {
            resumeThreads();
            try {
              Core.getGlobalContext().afterRestore(null);
            } catch (RestoreException re) {
              System.err.println("Error when calling afterRestore()");
              re.printStackTrace();
            }
          }
        }
      } catch (IOException ioe) {
        System.err.println("Can't accept/read/write on port " + port);
        ioe.printStackTrace();
      }
    }
  }

  private static void start() {
    Thread t = new Thread(new SuspendResumeAgent(), "SuspendResumeAgent");
    t.setDaemon(true);
    t.start();
  }

  public static void premain(String agentArgs, Instrumentation inst) {
    start();
  }

  public static void main(String args[]) {
    start();
  }
}
