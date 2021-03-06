/*
 * Copyright 2012-2017 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.boot.devtools.filewatch;

import java.io.File;
import java.io.FileFilter;
import java.io.IOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.WatchEvent;
import java.nio.file.WatchEvent.Kind;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.boot.devtools.filewatch.ChangedFile.Type;
import org.springframework.boot.devtools.remote.client.ClassPathChangeUploader;
import org.springframework.util.Assert;

/**
 * Starts a thread to monitor source directories using java.nio package.
 *
 * @author Damian Suchodolski
 *
 */
public class FileSystemNIOWatcher extends FileSystemWatcher implements FileWatcher {

	private static final int DEFAULT_TIMER_DELAY = 100;

	private static final Log logger = LogFactory.getLog(ClassPathChangeUploader.class);

	private final List<FileChangeListener> listeners = new ArrayList<>();

	private final Object addMonitor = new Object();
	private final Object closeMonitor = new Object();

	private final List<Path> registeredFolders = new LinkedList<Path>();

	private AtomicBoolean running = new AtomicBoolean(false);

	private Timer timer;

	private FileFilter triggerFilter;

	private final Map<WatchKey, Path> watchKeyPathMap = new HashMap<WatchKey, Path>();

	private Map<Path, WatchKey> watchPathKeyMap = new HashMap<Path, WatchKey>();

	private WatchService watchService;

	private Thread watchThread;

	private int pollIntervalMs = DEFAULT_TIMER_DELAY;

	private final int quietPeriodMs;

	public FileSystemNIOWatcher(Duration pollInterval, Duration quietPeriod) {
		logger.debug("new FileSystemListener");

		Assert.notNull(pollInterval, "PollInterval must not be null");
		Assert.notNull(quietPeriod, "QuietPeriod must not be null");
		Assert.isTrue(pollInterval.toMillis() > 0, "PollInterval must be positive");
		Assert.isTrue(quietPeriod.toMillis() >= 0, "QuietPeriod must be positive");
		Assert.isTrue(pollInterval.toMillis() < Integer.MAX_VALUE,
				"PollInterval must be smaller than " + Integer.MAX_VALUE);
		Assert.isTrue(quietPeriod.toMillis() < Integer.MAX_VALUE,
				"QuietPeriod must be smaller than " + Integer.MAX_VALUE);

		this.pollIntervalMs = (int) pollInterval.toMillis();
		this.quietPeriodMs = (int) quietPeriod.toMillis();

		logger.debug("pollIntervalMs :" + pollIntervalMs + ", quietPeriodMs :"
				+ quietPeriodMs);

	}

	@Override
	public void addListener(FileChangeListener fileChangeListener) {
		Assert.notNull(fileChangeListener, "FileChangeListener must not be null");
		synchronized (this.addMonitor) {
			logger.debug("new FileChangeListener " + fileChangeListener);
			this.listeners.add(fileChangeListener);
		}
	}

	@Override
	public void addSourceFolder(File folder) {
		Assert.notNull(folder, "Folder must not be null");
		Assert.isTrue(folder.isDirectory(),
				"Folder '" + folder + "' must exist and must" + " be a directory");
		logger.debug("new source folder " + folder);
		this.registeredFolders.add(folder.toPath());
	}

	@Override
	public void addSourceFolders(Iterable<File> folders) {
		Assert.notNull(folders, "Folders must not be null");
		synchronized (this.addMonitor) {
			for (File folder : folders) {
				addSourceFolder(folder);
			}
		}
	}

	private void fireListeners(Set<ChangedFiles> changeSet) {
		if (this.listeners == null) {
			return;
		}
		for (FileChangeListener listener : this.listeners) {
			listener.onChange(changeSet);
		}
	}

	private boolean acceptChangedFile(Path dir) {
		boolean accept = (this.triggerFilter == null
				|| !this.triggerFilter.accept(dir.toFile()));
		if (!accept) {
			logger.debug("Skip file : " + dir);
		}
		return accept;
	}

	private boolean isRecursiveEnabled() {
		return true;
	}

	private synchronized void registerWatch(Path dir) {
		logger.debug("Registering a path " + dir);
		if (!this.watchPathKeyMap.containsKey(dir)) {
			try {
				WatchKey watchKey = dir.register(this.watchService,
						java.nio.file.StandardWatchEventKinds.ENTRY_CREATE,
						java.nio.file.StandardWatchEventKinds.ENTRY_DELETE,
						java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY);
				this.watchPathKeyMap.put(dir, watchKey);
				this.watchKeyPathMap.put(watchKey, dir);
			}
			catch (IOException e) {
			}
		}
	}

	@Override
	public void setTriggerFilter(FileFilter triggerFilter) {
		synchronized (this.addMonitor) {
			this.triggerFilter = triggerFilter;
		}
	}

	@Override
	public void start() {
		logger.debug("Starting watchThread ...");
		synchronized (this.closeMonitor) {
			this.watchThread = new Thread(new Runnable() {
				@Override
				public void run() {
					FileSystemNIOWatcher.this.running.set(true);
					watchLoop();
				}
			}, "FileSystemListener");
			this.watchThread.start();
		}
	}

	public boolean isAlive() {
		if (this.watchThread == null) {
			return false;
		}
		return this.watchThread.isAlive();
	}

	@Override
	public void stop() {

		logger.debug("Closing watcher ...");
		synchronized (this.closeMonitor) {
			if (this.watchThread != null) {
				try {
					this.running.set(false);
					this.watchService.close();
					this.watchThread.interrupt();
				}
				catch (IOException e) {
					logger.debug("Problem clising watchThread", e);
				}
			}
		}

	}

	private synchronized void unregisterInvalidPaths() {
		Set<Path> paths = new HashSet<Path>(this.watchPathKeyMap.keySet());
		Set<Path> invalidPaths = new HashSet<Path>();

		for (Path path : paths) {
			if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
				invalidPaths.add(path);
			}
		}
		if (invalidPaths.size() > 0) {
			logger.debug("Removing invalid paths");
			for (Path path : invalidPaths) {
				unregisterWatch(path);
			}
		}
	}

	private synchronized void unregisterWatch(Path dir) {
		WatchKey watchKey = this.watchPathKeyMap.get(dir);
		if (watchKey != null) {
			watchKey.cancel();
			this.watchPathKeyMap.remove(dir);
		}
	}

	/**
	 * Register directories to watch recursively.
	 *
	 * @param path Path to examine
	 * @throws IOException exception
	 */
	private synchronized void walkTreeAndSetWatches(Path path) throws IOException {

		if (acceptChangedFile(path)) {
			Files.walkFileTree(path, new SimpleFileVisitor<Path>() {
				@Override
				public FileVisitResult preVisitDirectory(Path dir,
						BasicFileAttributes attrs) throws IOException {
					if (!acceptChangedFile(dir)) {
						return FileVisitResult.SKIP_SUBTREE;
					}
					else {
						registerWatch(dir);
						return FileVisitResult.CONTINUE;
					}
				}
			});
		}
	}

	private void watchLoop() {
		try {
			this.watchService = FileSystems.getDefault().newWatchService();
		}
		catch (IOException e) {
			logger.error("IO exception", e);
		}

		for (Path path : this.registeredFolders) {
			try {
				logger.debug("Initialize watched folder set");
				walkTreeAndSetWatches(path);
			}
			catch (IOException e) {
				logger.warn("Can't register a path", e);
			}
		}

		if (this.quietPeriodMs > 0) {
			try {
				Thread.sleep(this.quietPeriodMs);
			}
			catch (InterruptedException e) {
				logger.trace("Quiet period sleep interrupted");
			}
		}

		while (this.running.get()) {
			try {
				logger.debug("WatchThread waiting...");
				WatchKey watchKey = this.watchService.take();
				List<WatchEvent<?>> pollEvents = watchKey.pollEvents();

				synchronized (this.closeMonitor) {
					if (this.running.get()) {

						for (WatchEvent<?> event : pollEvents) {

							logger.debug("New event : " + event);

							if (event.context() instanceof Path) {

								Kind<?> kind = event.kind();

								if (kind == java.nio.file.StandardWatchEventKinds.OVERFLOW) {
									continue;
								}

								Path watchPath = this.watchKeyPathMap.get(watchKey);

								if (watchPath == null) {
									logger.debug("watch key is null " + watchKey);
									break;
								}

								Path eventPath = (Path) event.context();
								Path path = watchPath.resolve(eventPath);
								File file = path.toFile();

								logger.debug("New event : " + kind + ", eventPath : "
										+ eventPath + ", path: " + path);

								if (!acceptChangedFile(path)) {
									continue;
								}

								boolean isDirectory = file.isDirectory();

								if (this.timer != null) {
									this.timer.cancel();
									this.timer = null;
								}

								this.timer = new Timer("WatchTimer");
								this.timer.schedule(new TimerTask() {
									@Override
									public void run() {
										registerNewDirectories(kind, path, isDirectory);
										Type type = assignEventType(kind);
										// too many objects?
										Set<ChangedFile> changes = new LinkedHashSet<>();
										ChangedFiles changedFiles = new ChangedFiles(
												watchPath.toFile(), changes);
										Set<ChangedFiles> changedFilesSet = new HashSet<ChangedFiles>();
										changedFilesSet.add(changedFiles);

										if (logger.isDebugEnabled()) {
											logger.debug("Changed file : " + eventPath
													+ ", " + watchPath + ", " + file);
										}
										ChangedFile changedFile = new ChangedFile(
												watchPath.toFile(), file, type);
										changes.add(changedFile);

										fireListeners(Collections
												.unmodifiableSet(changedFilesSet));

										boolean valid = watchKey.reset();
										if (!valid) {
											FileSystemNIOWatcher.this.watchKeyPathMap
													.remove(watchKey);
										}

										unregisterInvalidPaths();

									}

									private void registerNewDirectories(Kind<?> kind,
											Path path, boolean isDirectory) {
										if (kind == java.nio.file.StandardWatchEventKinds.ENTRY_CREATE
												&& isDirectory && isRecursiveEnabled()) {

											try {
												walkTreeAndSetWatches(path);
											}
											catch (IOException e) {
												logger.warn("Can't register new folder",
														e);
											}
										}
									}

									private Type assignEventType(Kind<?> kind) {
										Type type = null;
										if (kind.equals(
												java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY)) {
											type = Type.MODIFY;
										}
										else if (kind.equals(
												java.nio.file.StandardWatchEventKinds.ENTRY_DELETE)) {
											type = Type.DELETE;
										}
										else if (kind.equals(
												java.nio.file.StandardWatchEventKinds.ENTRY_CREATE)) {
											type = Type.ADD;
										}
										else {
											logger.warn(
													"Invalid watch event type " + type);
										}
										return type;
									}

								}, this.pollIntervalMs);
							}

						}
					}
				}
			}
			catch (InterruptedException | ClosedWatchServiceException e) {
				logger.debug("Stopping watcher");
				logger.trace("Stopping watcher exception", e);
				stop();
			}
		}

	}

}
