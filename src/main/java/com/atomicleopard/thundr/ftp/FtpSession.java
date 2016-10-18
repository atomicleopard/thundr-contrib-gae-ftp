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
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Iterator;
import java.util.concurrent.Callable;

import org.apache.commons.net.ftp.FTPFile;
import org.apache.commons.net.ftp.FTPListParseEngine;
import org.apache.commons.net.ftp.FTPReply;

import com.atomicleopard.thundr.ftp.commons.FTPClient;
import com.google.apphosting.api.ApiProxy;
import com.google.apphosting.api.ApiProxy.Environment;
import com.threewks.thundr.logger.Logger;

public class FtpSession implements AutoCloseable {
	protected FTPClient preparedClient;

	public FtpSession(FTPClient preparedClient) {
		this.preparedClient = preparedClient;
	}

	/**
	 * Return the underlying {@link FTPClient}, which has already been prepared, in that its a logged in
	 * user with initial sensible settings already applied.
	 * 
	 * @return
	 */
	public FTPClient getPreparedClient() {
		return preparedClient;
	}

	@Override
	public void close() {
		if (preparedClient.isConnected()) {
			try {
				preparedClient.disconnect();
			} catch (IOException e) {
				Logger.warn("Failed to disconnect ftp session - ignoring: %s", e.getMessage());
			}
		}
	}

	public boolean deleteDirectory(final String directory) {
		return timeLogAndCatch("Delete directory", new Callable<Boolean>() {
			@Override
			public Boolean call() throws Exception {
				return preparedClient.removeDirectory(directory);
			}
		});
	}

	public boolean createDirectory(final String directory) {
		return timeLogAndCatch("Create directory", new Callable<Boolean>() {
			@Override
			public Boolean call() throws Exception {
				return preparedClient.makeDirectory(directory);
			}
		});
	}

	public boolean changeWorkingDirectory(final String directory) {
		return timeLogAndCatch("Change working directory", new Callable<Boolean>() {
			@Override
			public Boolean call() throws Exception {
				return preparedClient.changeWorkingDirectory(directory);
			}
		});
	}

	public boolean putFile(final String filename, final InputStream is) {
		return timeLogAndCatch("Put file", new Callable<Boolean>() {
			@Override
			public Boolean call() throws Exception {
				return preparedClient.storeFile(filename, is);
			}
		});
	}

	public boolean getFile(final String filename, final OutputStream os) {
		return timeLogAndCatch("Get file " + filename, new Callable<Boolean>() {
			@Override
			public Boolean call() throws Exception {
				return preparedClient.retrieveFile(filename, os);
			}
		});
	}

	public boolean getFile(FTPFile file, OutputStream os) {
		return file != null && file.isFile() && getFile(file.getName(), os);
	}

	public boolean renameFile(final String from, final String to) {
		return timeLogAndCatch("Rename file", new Callable<Boolean>() {
			@Override
			public Boolean call() throws Exception {
				return preparedClient.rename(from, to);
			}
		});
	}

	public boolean deleteFile(final String file) {
		return timeLogAndCatch("Delete file", new Callable<Boolean>() {
			@Override
			public Boolean call() throws Exception {
				return preparedClient.deleteFile(file);
			}
		});
	}

	public FTPFile[] listDirectories() {
		return timeLogAndCatch("List directories", new Callable<FTPFile[]>() {
			@Override
			public FTPFile[] call() throws Exception {
				return preparedClient.listDirectories();
			}
		});
	}

	public FTPFile[] listFiles() {
		return timeLogAndCatch("List files", new Callable<FTPFile[]>() {
			@Override
			public FTPFile[] call() throws Exception {
				return preparedClient.listFiles();
			}
		});
	}

	public FTPFile[] listDirectories(final String directory) {
		return timeLogAndCatch("List directories", new Callable<FTPFile[]>() {
			@Override
			public FTPFile[] call() throws Exception {
				return preparedClient.listDirectories(directory);
			}
		});
	}

	public FTPFile[] listFiles(final String directory) {
		return timeLogAndCatch("List files", new Callable<FTPFile[]>() {
			@Override
			public FTPFile[] call() throws Exception {
				return preparedClient.listFiles(directory);
			}
		});
	}

	/**
	 * List remote contents in batches - includes both files and directories
	 * 
	 * @param directory
	 * @param batchSize
	 * @return
	 */
	public Iterable<FTPFile[]> listBatch(final String directory, final int batchSize) {
		return timeLogAndCatch("List files in batch", new Callable<Iterable<FTPFile[]>>() {
			@Override
			public Iterable<FTPFile[]> call() throws Exception {
				final FTPListParseEngine engine = preparedClient.initiateListParsing(directory);
				return new Iterable<FTPFile[]>() {
					@Override
					public Iterator<FTPFile[]> iterator() {
						return new Iterator<FTPFile[]>() {

							@Override
							public boolean hasNext() {
								return engine.hasNext();
							}

							@Override
							public FTPFile[] next() {
								return engine.getNext(batchSize);
							}

							@Override
							public void remove() {
								throw new UnsupportedOperationException();
							}
						};
					}
				};
			}
		});
	}

	public <T> T timeLogAndCatch(String command, Callable<T> callable) {
		long start = System.currentTimeMillis();
		Environment env = ApiProxy.getCurrentEnvironment();
		Double origVal = (Double) env.getAttributes().put(FtpClient.API_DEADLINE_KEY, 60000.0);
		try {
			Logger.info("Ftp %s", command);
			T result = callable.call();
			long end = System.currentTimeMillis();
			Logger.info("Ftp %s succeeded in %sms", command, (end - start));
			return result;
		} catch (Exception e) {
			long end = System.currentTimeMillis();
			Logger.warn("Ftp %s failed in %sms: %s", command, (end - start), e.getMessage());
			throw new FtpException(e, "Ftp failed - %s: %s", command, e.getMessage());
		} finally {
			if (origVal == null) {
				env.getAttributes().remove(FtpClient.API_DEADLINE_KEY);
			} else {
				env.getAttributes().put(FtpClient.API_DEADLINE_KEY, origVal);
			}
		}
	}

	public InputStream getFile(final String filename) {
		return timeLogAndCatch("Get file stream " + filename, new Callable<InputStream>() {
			@Override
			public InputStream call() throws Exception {
				final InputStream is = preparedClient.retrieveFileStream(filename);
				int reply = preparedClient.getReplyCode();
				if (is == null || (!FTPReply.isPositivePreliminary(reply) && !FTPReply.isPositiveCompletion(reply))) {
					throw new FtpException("Failed to open input stream: %s", preparedClient.getReplyString());
				}

				return new InputStream() {
					@Override
					public int read() throws IOException {
						return is.read();
					}

					@Override
					public int read(byte[] b) throws IOException {
						return is.read(b);
					}

					@Override
					public int read(byte[] b, int off, int len) throws IOException {
						return is.read(b, off, len);
					}

					@Override
					public void close() throws IOException {
						is.close();
						preparedClient.completePendingCommand();
					}
				};
			}
		});
	}
}
