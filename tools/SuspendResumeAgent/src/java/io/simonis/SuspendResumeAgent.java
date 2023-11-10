package io.simonis;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.lang.instrument.Instrumentation;
import java.lang.management.ManagementFactory;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Arrays;
import javax.management.MBeanServer;
import javax.management.ObjectName;

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

  private static MBeanServer mbserver;
  private static ObjectName diagCmd;

  static void loadDiagnosticCommandMBean() {
    try {
      mbserver = ManagementFactory.getPlatformMBeanServer();
      diagCmd = new ObjectName("com.sun.management:type=DiagnosticCommand");
    } catch (Exception e) {
      System.err.println("Can't load com.sun.management:type=DiagnosticCommand");
      e.printStackTrace();
    }
  }

  static {
    // System.out.println(SuspendResumeAgent.class.getResource("/libSuspendResumeAgent.so"));
    loadNativeLibrary();
    loadDiagnosticCommandMBean();
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
              out.println("Error when calling beforeCheckpoint()");
              ce.printStackTrace(out);
            }
            forceGC();
            executeJcmd("System.zero_unused_memory", new String[] {}, out);
            executeJcmd("System.trim_native_heap", new String[] {}, out);
            suspendThreads();
            client.close();
            break;
          } else if ("RESUME".equals(line)) {
            resumeThreads();
            try {
              Core.getGlobalContext().afterRestore(null);
            } catch (RestoreException re) {
              out.println("Error when calling afterRestore()");
              re.printStackTrace(out);
            }
          } else if (line.startsWith("JCMD")) {
            String[] split = line.split("\\h");
            if (split.length > 1) {
              String cmd = split[1];
              String[] args = Arrays.copyOfRange(split, 2, split.length);
              executeJcmd(cmd, args, out);
            }
          }
        }
      } catch (IOException ioe) {
        System.err.println("Can't accept/read/write on port " + port);
        ioe.printStackTrace();
      }
    }
  }

  private static void executeJcmd(String cmd, String[] args, PrintWriter out) {
    System.out.println("Executing: jcmd " + cmd + " " + Arrays.toString(args));
    if (mbserver != null && diagCmd != null) {
      try {
        String res = (String)mbserver
          .invoke(diagCmd , transform(cmd),
                  new Object[] { args },
                  new String[] { String[].class.getName()} );
        out.println(res);
      } catch (Exception e) {
        out.println("Error when executing JCMD " + cmd);
        e.printStackTrace(out);
      }
    }
  }

  /*
   * Verbose copy from com.sun.management.internal.DiagnosticCommandImpl::transform
   */
  private static String transform(String name) {
    StringBuilder sb = new StringBuilder();
    boolean toLower = true;
    boolean toUpper = false;
    for (int i = 0; i < name.length(); i++) {
      char c = name.charAt(i);
      if (c == '.' || c == '_') {
        toLower = false;
        toUpper = true;
      } else {
        if (toUpper) {
          toUpper = false;
          sb.append(Character.toUpperCase(c));
        } else if(toLower) {
          sb.append(Character.toLowerCase(c));
        } else {
          sb.append(c);
        }
      }
    }
    return sb.toString();
  }

  private static void start(boolean daemon) {
    Thread t = new Thread(new SuspendResumeAgent(), "SuspendResumeAgent");
    t.setDaemon(daemon);
    t.start();
  }

  public static void premain(String agentArgs, Instrumentation inst) {
    start(true);
  }

  public static void main(String args[]) {
    start(false);
  }
}
