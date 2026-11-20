/*
 * Copyright 2026 Ehviewer SMB Saver fork
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.hippo.ehviewer.homelab.sr;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** A stand-in for the companion on a plain socket, one request at a time; it records what it was sent. */
final class FakeCompanion implements AutoCloseable {

    final AtomicInteger requests = new AtomicInteger();
    final CountDownLatch requested = new CountDownLatch(1);
    final CountDownLatch release = new CountDownLatch(1);
    volatile boolean holdAnswer;
    volatile int status = 200;
    volatile byte[] answer = new byte[0];
    volatile String method;
    volatile String target;
    volatile String authorization;
    volatile byte[] body;

    private final ServerSocket mSocket;

    FakeCompanion() throws IOException {
        mSocket = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"));
        Thread thread = new Thread(this::serve, "fake-companion");
        thread.setDaemon(true);
        thread.start();
    }

    String baseUrl() {
        return "http://127.0.0.1:" + mSocket.getLocalPort();
    }

    private void serve() {
        while (!mSocket.isClosed()) {
            try (Socket socket = mSocket.accept()) {
                handle(socket);
            } catch (IOException | InterruptedException e) {
                // Closed by the test, or the client hung up.
            }
        }
    }

    private void handle(Socket socket) throws IOException, InterruptedException {
        InputStream in = new BufferedInputStream(socket.getInputStream());
        String[] requestLine = readLine(in).split(" ");
        if (requestLine.length < 2) {
            return;
        }
        int length = 0;
        String auth = null;
        for (String line = readLine(in); !line.isEmpty(); line = readLine(in)) {
            int colon = line.indexOf(':');
            String name = line.substring(0, colon).trim();
            String value = line.substring(colon + 1).trim();
            if (name.equalsIgnoreCase("Content-Length")) {
                length = Integer.parseInt(value);
            } else if (name.equalsIgnoreCase("Authorization")) {
                auth = value;
            }
        }
        byte[] data = new byte[length];
        for (int read = 0, n; read < length && (n = in.read(data, read, length - read)) >= 0; read += n) {
            // Reads the whole body.
        }
        method = requestLine[0];
        target = requestLine[1];
        authorization = auth;
        body = data;
        requests.incrementAndGet();
        requested.countDown();
        if (holdAnswer) {
            release.await(30, TimeUnit.SECONDS);
        }
        OutputStream out = socket.getOutputStream();
        byte[] reply = answer;
        out.write(("HTTP/1.1 " + status + " Fake\r\nContent-Length: " + reply.length
                + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        out.write(reply);
        out.flush();
    }

    private static String readLine(InputStream in) throws IOException {
        StringBuilder line = new StringBuilder();
        for (int c = in.read(); c != -1 && c != '\n'; c = in.read()) {
            if (c != '\r') {
                line.append((char) c);
            }
        }
        return line.toString();
    }

    @Override
    public void close() throws IOException {
        release.countDown();
        mSocket.close();
    }
}
