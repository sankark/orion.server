/*******************************************************************************
 * Copyright (c) 2011, 2012 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 * 
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package org.eclipse.orion.server.git.servlets;

import java.io.*;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.*;
import org.eclipse.core.filesystem.EFS;
import org.eclipse.core.filesystem.IFileStore;
import org.eclipse.core.runtime.*;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.RepositoryCache;
import org.eclipse.jgit.util.FS;
import org.eclipse.orion.internal.server.servlets.Activator;
import org.eclipse.orion.internal.server.servlets.file.NewFileServlet;
import org.eclipse.orion.internal.server.servlets.workspace.WebProject;
import org.eclipse.orion.internal.server.servlets.workspace.WebWorkspace;
import org.eclipse.orion.server.git.GitConstants;
import org.eclipse.orion.server.git.GitCredentialsProvider;
import org.json.JSONObject;

public class GitUtils {

	public enum Traverse {
		GO_UP, GO_DOWN, CURRENT
	}

	/**
	 * Returns the file representing the Git repository directory for the given 
	 * file path or any of its parent in the filesystem. If the file doesn't exits,
	 * is not a Git repository or an error occurred while transforming the given
	 * path into a store <code>null</code> is returned.
	 *
	 * @param path expected format /file/{Workspace}/{projectName}[/{path}]
	 * @return the .git folder if found or <code>null</code> the give path
	 * cannot be resolved to a file or it's not under control of a git repository
	 * @throws CoreException
	 */
	public static File getGitDir(IPath path) throws CoreException {
		Map<IPath, File> gitDirs = GitUtils.getGitDirs(path, Traverse.GO_UP);
		if (gitDirs == null)
			return null;
		Collection<File> values = gitDirs.values();
		if (values.isEmpty())
			return null;
		return values.toArray(new File[] {})[0];
	}

	public static File getGitDir(File file) {
		if (file.exists()) {
			while (file != null) {
				if (RepositoryCache.FileKey.isGitRepository(file, FS.DETECTED)) {
					return file;
				} else if (RepositoryCache.FileKey.isGitRepository(new File(file, Constants.DOT_GIT), FS.DETECTED)) {
					return new File(file, Constants.DOT_GIT);
				}
				file = file.getParentFile();
			}
		}
		return null;
	}

	/**
	 * Returns the existing git repositories for the given file path, following
	 * the given traversal rule.
	 * 
	 * @param path expected format /file/{Workspace}/{projectName}[/{path}]
	 * @return a map of all git repositories found, or <code>null</code>
	 * if the provided path format doesn't match the expected format.
	 * @throws CoreException
	 */
	public static Map<IPath, File> getGitDirs(IPath path, Traverse traverse) throws CoreException {
		IPath p = path.removeFirstSegments(1);//remove /file
		IFileStore fileStore = NewFileServlet.getFileStore(p);
		if (fileStore == null)
			return null;
		Map<IPath, File> result = new HashMap<IPath, File>();
		File file = fileStore.toLocalFile(EFS.NONE, null);
		//jgit can only handle a local file
		if (file == null)
			return result;
		switch (traverse) {
			case CURRENT :
				if (RepositoryCache.FileKey.isGitRepository(file, FS.DETECTED)) {
					result.put(new Path(""), file); //$NON-NLS-1$
				} else if (RepositoryCache.FileKey.isGitRepository(new File(file, Constants.DOT_GIT), FS.DETECTED)) {
					result.put(new Path(""), new File(file, Constants.DOT_GIT)); //$NON-NLS-1$
				}
				break;
			case GO_UP :
				getGitDirsInParents(file, result);
				break;
			case GO_DOWN :
				getGitDirsInChildren(file, p, result);
				break;
		}
		return result;
	}

	private static void getGitDirsInParents(File file, Map<IPath, File> gitDirs) {
		int levelUp = 0;
		File workspaceRoot = org.eclipse.orion.internal.server.servlets.Activator.getDefault().getPlatformLocation().toFile();
		while (file != null && !file.getAbsolutePath().equals(workspaceRoot.getAbsolutePath())) {
			if (file.exists()) {
				if (RepositoryCache.FileKey.isGitRepository(file, FS.DETECTED)) {
					gitDirs.put(getPathForLevelUp(levelUp), file);
					return;
				} else if (RepositoryCache.FileKey.isGitRepository(new File(file, Constants.DOT_GIT), FS.DETECTED)) {
					gitDirs.put(getPathForLevelUp(levelUp), new File(file, Constants.DOT_GIT));
					return;
				}
			}
			file = file.getParentFile();
			levelUp++;
		}
		return;
	}

	private static IPath getPathForLevelUp(int levelUp) {
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < levelUp; i++) {
			sb.append("../"); //$NON-NLS-1$
		}
		return new Path(sb.toString());
	}

	/**
	 * Recursively walks down a directory tree and collects the paths of all git repositories.
	 */
	private static void getGitDirsInChildren(File localFile, IPath path, Map<IPath, File> gitDirs) throws CoreException {
		if (localFile.exists() && localFile.isDirectory()) {
			if (RepositoryCache.FileKey.isGitRepository(localFile, FS.DETECTED)) {
				gitDirs.put(path.addTrailingSeparator(), localFile);
				return;
			} else if (RepositoryCache.FileKey.isGitRepository(new File(localFile, Constants.DOT_GIT), FS.DETECTED)) {
				gitDirs.put(path.addTrailingSeparator(), new File(localFile, Constants.DOT_GIT));
				return;
			}
			File[] folders = localFile.listFiles(new FileFilter() {
				public boolean accept(File file) {
					return file.isDirectory() && !file.getName().equals(Constants.DOT_GIT);
				}
			});
			for (File folder : folders) {
				getGitDirsInChildren(folder, path.append(folder.getName()), gitDirs);
			}
			return;
		}
	}

	public static String getRelativePath(IPath filePath, IPath pathToGitRoot) {
		StringBuilder sb = new StringBuilder();
		String file = null;
		if (!filePath.hasTrailingSeparator()) {
			file = filePath.lastSegment();
			filePath = filePath.removeLastSegments(1);
		}
		for (int i = 0; i < pathToGitRoot.segments().length; i++) {
			if (pathToGitRoot.segments()[i].equals(".."))
				sb.append(filePath.segment(filePath.segments().length - pathToGitRoot.segments().length + i)).append("/");
			// else TODO
		}
		if (file != null)
			sb.append(file);
		return sb.toString();
	}

	static GitCredentialsProvider createGitCredentialsProvider(final JSONObject json) {
		String username = json.optString(GitConstants.KEY_USERNAME, null);
		char[] password = json.optString(GitConstants.KEY_PASSWORD, "").toCharArray(); //$NON-NLS-1$
		String knownHosts = json.optString(GitConstants.KEY_KNOWN_HOSTS, null);
		byte[] privateKey = json.optString(GitConstants.KEY_PRIVATE_KEY, "").getBytes(); //$NON-NLS-1$
		byte[] publicKey = json.optString(GitConstants.KEY_PUBLIC_KEY, "").getBytes(); //$NON-NLS-1$
		byte[] passphrase = json.optString(GitConstants.KEY_PASSPHRASE, "").getBytes(); //$NON-NLS-1$

		GitCredentialsProvider cp = new GitCredentialsProvider(null /* set by caller */, username, password, knownHosts);
		cp.setPrivateKey(privateKey);
		cp.setPublicKey(publicKey);
		cp.setPassphrase(passphrase);
		return cp;
	}

	public static String encode(String s) {
		try {
			return URLEncoder.encode(s, "UTF-8"); //$NON-NLS-1$
		} catch (UnsupportedEncodingException e) {
			// should never happen since "UTF-8" is used
		}
		return s;
	}

	public static String decode(String s) {
		try {
			return URLDecoder.decode(s, "UTF-8"); //$NON-NLS-1$
		} catch (UnsupportedEncodingException e) {
			// should never happen since "UTF-8" is used
		}
		return s;
	}

	/**
	 * Returns the existing WebProject corresponding to the provided path, 
	 * or <code>null</code> if no such project exists.
	 * @param path path in the form /file/{workspaceId}/{projectName}/[filePath]
	 * @return the web project, or <code>null</code>
	 */
	public static WebProject projectFromPath(IPath path) {
		if (path == null || path.segmentCount() < 3)
			return null;
		String workspaceId = path.segment(1);
		if (!WebWorkspace.exists(workspaceId))
			return null;
		WebWorkspace workspace = WebWorkspace.fromId(workspaceId);
		return workspace.getProjectByName(path.segment(2));
	}

	/**
	 * Returns the HTTP path for the content resource of the given project.
	 * @param workspace The web workspace
	 * @param project The web project 
	 * @return the HTTP path of the project content resource
	 */
	public static IPath pathFromProject(WebWorkspace workspace, WebProject project) {
		return new Path(Activator.LOCATION_FILE_SERVLET).append(workspace.getId()).append(project.getName());

	}
}
