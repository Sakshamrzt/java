/*
Copyright 2020 The Kubernetes Authors.
Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at
http://www.apache.org/licenses/LICENSE-2.0
Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
*/
package io.kubernetes.client;

import static io.kubernetes.client.KubernetesConstants.*;

import com.google.common.io.CharStreams;
import com.google.gson.reflect.TypeToken;
import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.Configuration;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import io.kubernetes.client.openapi.models.V1Pod;
import io.kubernetes.client.openapi.models.V1Status;
import io.kubernetes.client.openapi.models.V1StatusCause;
import io.kubernetes.client.openapi.models.V1StatusDetails;
import io.kubernetes.client.util.WebSocketStreamHandler;
import io.kubernetes.client.util.WebSockets;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.Reader;
import java.io.UnsupportedEncodingException;
import java.lang.reflect.Type;
import java.net.URLEncoder;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Exec {
  private static final Logger log = LoggerFactory.getLogger(Exec.class);

  private ApiClient apiClient;

  /** Simple Exec API constructor, uses default configuration */
  public Exec() {
    this(Configuration.getDefaultApiClient());
  }

  /**
   * Exec API Constructor
   *
   * @param apiClient The api client to use.
   */
  public Exec(ApiClient apiClient) {
    this.apiClient = apiClient;
  }

  /**
   * Get the API client for these exec operations.
   *
   * @return The API client that will be used.
   */
  public ApiClient getApiClient() {
    return apiClient;
  }

  /**
   * Set the API client for subsequent exec operations.
   *
   * @param apiClient The new API client to use.
   */
  public void setApiClient(ApiClient apiClient) {
    this.apiClient = apiClient;
  }

  /**
   * Setup a Builder for the given namespace, name and command
   *
   * @param namespace The namespace of the Pod
   * @param name The name of the Pod
   * @param command The command to run
   */
  public ExecutionBuilder newExecutionBuilder(String namespace, String name, String[] command) {
    return new ExecutionBuilder(namespace, name, command);
  }

  /**
   * Execute a command in a container. If there are multiple containers in the pod, uses the first
   * container in the Pod.
   *
   * @param namespace The namespace of the Pod
   * @param name The name of the Pod
   * @param command The command to run
   * @param stdin If true, pass a stdin stream into the container
   */
  public Process exec(String namespace, String name, String[] command, boolean stdin)
      throws ApiException, IOException {
    return exec(namespace, name, command, null, stdin, false);
  }

  /**
   * Execute a command in a container. If there are multiple containers in the pod, uses the first
   * container in the Pod.
   *
   * @param pod The pod where the command is run.
   * @param command The command to run
   * @param stdin If true, pass a stdin stream into the container
   */
  public Process exec(V1Pod pod, String[] command, boolean stdin) throws ApiException, IOException {
    return exec(pod, command, pod.getSpec().getContainers().get(0).getName(), stdin, false);
  }

  /**
   * Execute a command in a container. If there are multiple containers in the pod, uses the first
   * container in the Pod.
   *
   * @param namespace The namespace of the Pod
   * @param name The name of the Pod
   * @param command The command to run
   * @param stdin If true, pass a stdin stream into the container
   * @param tty If true, stdin is a tty.
   */
  public Process exec(String namespace, String name, String[] command, boolean stdin, boolean tty)
      throws ApiException, IOException {
    return exec(namespace, name, command, null, stdin, tty);
  }

  /**
   * Execute a command in a container. If there are multiple containers in the pod, uses the first
   * container in the Pod.
   *
   * @param pod The pod where the command is run.
   * @param command The command to run
   * @param stdin If true, pass a stdin stream into the container
   * @param tty If true, stdin is a tty.
   */
  public Process exec(V1Pod pod, String[] command, boolean stdin, boolean tty)
      throws ApiException, IOException {
    return exec(pod, command, pod.getSpec().getContainers().get(0).getName(), stdin, tty);
  }

  /**
   * Execute a command in a container. If there are multiple containers in the pod, uses the first
   * container in the Pod.
   *
   * @param pod The pod where the command is run.
   * @param command The command to run
   * @param container The container in the Pod where the command is run.
   * @param stdin If true, pass a stdin stream into the container.
   * @param tty If true, stdin is a TTY (only applies if stdin is true)
   */
  public Process exec(V1Pod pod, String[] command, String container, boolean stdin, boolean tty)
      throws ApiException, IOException {
    if (container == null) {
      container = pod.getSpec().getContainers().get(0).getName();
    }
    return exec(
        pod.getMetadata().getNamespace(),
        pod.getMetadata().getName(),
        command,
        container,
        stdin,
        tty);
  }

  /**
   * Execute a command in a container. If there are multiple containers in the pod, uses the first
   * container in the Pod.
   *
   * @param namespace The namespace of the Pod
   * @param name The name of the Pod
   * @param command The command to run
   * @param container The container in the Pod where the command is run.
   * @param stdin If true, pass a stdin stream into the container.
   * @param tty If true, stdin is a TTY (only applies if stdin is true)
   */
  public Process exec(
      String namespace, String name, String[] command, String container, boolean stdin, boolean tty)
      throws ApiException, IOException {
    return new ExecutionBuilder(namespace, name, command)
        .setContainer(container)
        .setStdin(stdin)
        .setTty(tty)
        .execute();
  }

  public final class ExecutionBuilder {
    private final String namespace;
    private final String name;
    private final String[] command;

    private String container;

    private boolean stdin;
    private boolean stdout;
    private boolean stderr;
    private boolean tty;

    private ExecutionBuilder(String namespace, String name, String[] command) {
      this.namespace = namespace;
      this.name = name;
      this.command = command;
      this.stdin = true;
      this.stdout = true;
      this.stderr = true;
    }

    public String getName() {
      return name;
    }

    public String getNamespace() {
      return namespace;
    }

    public String[] getCommand() {
      return command;
    }

    public String getContainer() {
      return container;
    }

    public ExecutionBuilder setContainer(String container) {
      this.container = container;
      return this;
    }

    public boolean getStdin() {
      return stdin;
    }

    public ExecutionBuilder setStdin(boolean stdin) {
      this.stdin = stdin;
      return this;
    }

    public boolean getStdout() {
      return stdout;
    }

    public ExecutionBuilder setStdout(boolean stdout) {
      this.stdout = stdout;
      return this;
    }

    public boolean getStderr() {
      return stderr;
    }

    public ExecutionBuilder setStderr(boolean stderr) {
      this.stderr = stderr;
      return this;
    }

    public boolean getTty() {
      return tty;
    }

    public ExecutionBuilder setTty(boolean tty) {
      this.tty = tty;
      return this;
    }

    private String makePath() {
      String[] encodedCommand = new String[command.length];
      for (int i = 0; i < command.length; i++) {
        try {
          encodedCommand[i] = URLEncoder.encode(command[i], "UTF-8");
        } catch (UnsupportedEncodingException ex) {
          throw new RuntimeException("some thing wrong happend: " + ex.getMessage());
        }
      }
      return "/api/v1/namespaces/"
          + namespace
          + "/pods/"
          + name
          + "/exec?"
          + "stdin="
          + stdin
          + "&stdout="
          + stdout
          + "&stderr="
          + stderr
          + "&tty="
          + tty
          + (container != null ? "&container=" + container : "")
          + "&command="
          + StringUtils.join(encodedCommand, "&command=");
    }

    public Process execute() throws ApiException, IOException {
      if (container == null) {
        CoreV1Api api = new CoreV1Api(apiClient);
        V1Pod pod = api.readNamespacedPod(name, namespace, "false", null, null);
        container = pod.getSpec().getContainers().get(0).getName();
      }

      ExecProcess exec = new ExecProcess(apiClient);
      WebSocketStreamHandler handler = exec.getHandler();
      WebSockets.stream(makePath(), "GET", apiClient, handler);

      return exec;
    }
  }

  static int parseExitCode(ApiClient client, InputStream inputStream) {
    try {
      Type returnType = new TypeToken<V1Status>() {}.getType();
      String body;
      try (final Reader reader = new InputStreamReader(inputStream)) {
        body = CharStreams.toString(reader);
      }

      V1Status status = client.getJSON().deserialize(body, returnType);
      if (status == null) {
        return -1;
      }
      if (V1STATUS_SUCCESS.equals(status.getStatus())) return 0;

      if (V1STATUS_REASON_NONZEROEXITCODE.equals(status.getReason())) {
        V1StatusDetails details = status.getDetails();
        if (details != null) {
          List<V1StatusCause> causes = details.getCauses();
          if (causes != null) {
            for (V1StatusCause cause : causes) {
              if (V1STATUS_CAUSE_REASON_EXITCODE.equals(cause.getReason())) {
                try {
                  return Integer.parseInt(cause.getMessage());
                } catch (NumberFormatException nfe) {
                  log.error("Error parsing exit code from status channel response", nfe);
                }
              }
            }
          }
        }
      }
    } catch (Throwable t) {
      log.error("Error parsing exit code from status channel response", t);
    }

    // Unable to parse the exit code from the content
    return -1;
  }

  public static class ExecProcess extends Process {
    private final WebSocketStreamHandler streamHandler;
    private int statusCode = -1;
    private boolean isAlive = true;
    private final Map<Integer, InputStream> input = new HashMap<>();
    private final CountDownLatch latch = new CountDownLatch(1);

    public ExecProcess(final ApiClient apiClient) throws IOException {
      this.streamHandler =
          new WebSocketStreamHandler() {
            @Override
            protected void handleMessage(int stream, InputStream inStream) throws IOException {
              if (stream == 3) {
                int exitCode = parseExitCode(apiClient, inStream);
                if (exitCode >= 0) {
                  // notify of process completion
                  synchronized (ExecProcess.this) {
                    statusCode = exitCode;
                    isAlive = false;
                  }
                }
                inStream.close();
                // Stream ID of `3` delivers the status of exec connection from
                // kubelet,
                // closing the connection upon 0 exit-code.
                this.close();
                ExecProcess.this.latch.countDown();
              } else super.handleMessage(stream, inStream);
            }

            @Override
            public void failure(Throwable ex) {
              super.failure(ex);
              // TODO, it's possible we should suppress this error message, but
              // currently there's
              // no good place to surface the message, and without it, this will be
              // really hard to
              // debug.
              ex.printStackTrace();
              synchronized (ExecProcess.this) {
                // Try for a pretty unique error code, so if someone searches
                // they'll find this
                // code.
                statusCode = -1975219;
                isAlive = false;
                ExecProcess.this.latch.countDown();
              }
            }

            @Override
            public void close() {
              // notify of process completion
              synchronized (ExecProcess.this) {
                if (isAlive) {
                  isAlive = false;
                  ExecProcess.this.latch.countDown();
                }
              }

              super.close();
            }
          };
    }

    // Protected to facilitate unit testing.
    protected WebSocketStreamHandler getHandler() {
      return streamHandler;
    }

    @Override
    public OutputStream getOutputStream() {
      return streamHandler.getOutputStream(0);
    }

    @Override
    public InputStream getInputStream() {
      return getInputStream(1);
    }

    @Override
    public InputStream getErrorStream() {
      return getInputStream(2);
    }

    public InputStream getConnectionErrorStream() {
      return getInputStream(3);
    }

    public OutputStream getResizeStream() {
      return streamHandler.getOutputStream(4);
    }

    private synchronized InputStream getInputStream(int stream) {
      if (!input.containsKey(stream)) {
        input.put(stream, streamHandler.getInputStream(stream));
      }
      return input.get(stream);
    }

    @Override
    public int waitFor() throws InterruptedException {
      this.latch.await();
      return statusCode;
    }

    @Override
    public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException {
      this.latch.await(timeout, unit);
      return !isAlive();
    }

    @Override
    public synchronized int exitValue() {
      if (isAlive) throw new IllegalThreadStateException();
      return statusCode;
    }

    @Override
    public synchronized boolean isAlive() {
      return isAlive;
    }

    @Override
    public void destroy() {
      streamHandler.close();
      for (InputStream in : input.values()) {
        try {
          in.close();
        } catch (IOException ex) {
          log.error("Error on close", ex);
        }
      }
    }
  }
}
