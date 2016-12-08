/*
 * This file is a part of thundr-contrib-ftp, a software library from Atomic Leopard.
 *
 * Copyright (C) 2016 Atomic Leopard, <nick@atomicleopard.com.au>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.atomicleopard.thundr.ftp;

import java.io.IOException;
import java.net.SocketException;
import java.util.HashMap;
import java.util.Map;

import org.apache.commons.net.ftp.FTPReply;
import org.apache.commons.net.io.CopyStreamEvent;
import org.apache.commons.net.io.CopyStreamListener;

import com.atomicleopard.thundr.ftp.commons.FTP;
import com.atomicleopard.thundr.ftp.commons.FTPClient;
import com.google.apphosting.api.ApiProxy;
import com.google.apphosting.api.ApiProxy.Environment;
import com.threewks.thundr.logger.Logger;

/**
 * {@link FtpClient} allows you to {@link #run(FtpOperation)} a ftp operations within the
 * GAE sandbox.
 */
public class FtpClient {
	public static final String API_DEADLINE_KEY = "com.google.apphosting.api.ApiProxy.api_deadline_key";
	public static final int PortFtp = 21;
	public static final int PortSftp = 22;

	protected String hostname;
	protected int port;
	protected String username;
	protected String password;
	protected int timeoutMillis = 60000;

	public FtpClient(String hostname, String username, String password) {
		this(hostname, PortFtp, username, password);
	}

	public FtpClient(String hostname, int port, String username, String password) {
		this.hostname = hostname;
		this.port = port;
		this.username = username;
		this.password = password;
	}

	public <T> T run(FtpOperation<T> operation) {
		try (FtpSession session = establishSession()) {
			return operation.run(session);
		}
	}

	protected FtpSession establishSession() {
		FTPClient ftpClient = prepareClient();
		return new FtpSession(ftpClient);
	}

	protected FTPClient prepareClient() {
		Environment env = ApiProxy.getCurrentEnvironment();
		Map<String, Object> attributes = env == null ? new HashMap<String, Object>() : env.getAttributes();
		Double origVal = (Double) attributes.put(API_DEADLINE_KEY, timeoutMillis / 1000.0);

		long start = System.currentTimeMillis();
		FTPClient client = new FTPClient();
		client.setDefaultTimeout(timeoutMillis);
		client.setCopyStreamListener(provideCopyStreamListener());
		try {
			try {
				connect(client);
				if (!FTPReply.isPositiveCompletion(client.getReplyCode())) {
					throw new FtpException("Ftp Failed - could not connect to %s:%s", hostname, port);
				}
				configureClient(client);
				if (!client.login(username, password)) {
					throw new FtpException("Ftp failed - login failed, credentials refused");
				}
				client.enterLocalPassiveMode();
				if (!FTPReply.isPositiveCompletion(client.getReplyCode())) {
					throw new FtpException("Ftp failed - could not switch to passive mode");
				}
				if (!client.setFileType(FTP.BINARY_FILE_TYPE)) {
					throw new FtpException("Ftp failed - could not switch to binary transfer mode");
				}
			} catch (IOException e) {
				throw new FtpException(e, "Ftp failed - could not establish a prepared ftp client: %s", e.getMessage());
			}
		} catch (FtpException e) {
			long complete = System.currentTimeMillis();
			Logger.warn("Took %sms to fail to establish an ftp connection to %s:%s", (complete - start), hostname, port);
			if (client.isConnected()) {
				try {
					client.logout();
				} catch (IOException ioe) {
					Logger.warn("Failed to logout after connection failed - ignoring: %s", e.getMessage());
				}
				try {
					client.disconnect();
				} catch (IOException ioe) {
					Logger.warn("Failed to disconnect after connection failed - ignoring: %s", e.getMessage());
				}
			}
			throw e;
		} finally {
			if (origVal == null) {
				attributes.remove(API_DEADLINE_KEY);
			} else {
				attributes.put(API_DEADLINE_KEY, origVal);
			}
		}
		long complete = System.currentTimeMillis();
		Logger.info("Took %sms to establish an ftp connection to %s:%s", (complete - start), hostname, port);
		return client;
	}

	/**
	 * Provide a {@link ProgressTrackingStreamListener} for the ftp operation. Return null if no
	 * listener is desired.
	 * 
	 * @return
	 */
	protected ProgressTrackingStreamListener provideCopyStreamListener() {
		return null;// new ProgressTrackingStreamListener();
	}

	protected void connect(FTPClient client) throws SocketException, IOException {
		client.connect(hostname, port);
	}

	protected void configureClient(FTPClient client) throws SocketException, IOException {
		client.setSoTimeout(timeoutMillis);
		client.setBufferSize(4 * 1024);
		// client.setControlKeepAliveTimeout(1);
		// client.setControlKeepAliveReplyTimeout(5000);
		client.setFileTransferMode(FTP.BLOCK_TRANSFER_MODE);
		// client.setUseEPSVwithIPv4(true);
	}

	public static class ProgressTrackingStreamListener implements CopyStreamListener {
		private long megsTotal = 0;

		// @Override
		@Override
		public void bytesTransferred(CopyStreamEvent event) {
			bytesTransferred(event.getTotalBytesTransferred(), event.getBytesTransferred(), event.getStreamSize());
		}

		// @Override
		@Override
		public void bytesTransferred(long totalBytesTransferred, int bytesTransferred, long streamSize) {
			long megs = totalBytesTransferred / 1000000;
			for (long l = megsTotal; l < megs; l++) {
				System.out.print("#");
			}
			megsTotal = megs;
		}
	}
}
