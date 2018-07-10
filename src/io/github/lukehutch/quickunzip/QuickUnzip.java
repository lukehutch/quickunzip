/*
 * The MIT License (MIT)
 *
 * Copyright (c) 2018 Luke Hutchison
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated
 * documentation files (the "Software"), to deal in the Software without restriction, including without
 * limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of
 * the Software, and to permit persons to whom the Software is furnished to do so, subject to the following
 * conditions:
 *
 * The above copyright notice and this permission notice shall be included in all copies or substantial
 * portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT
 * LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO
 * EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN
 * AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE
 * OR OTHER DEALINGS IN THE SOFTWARE.
 */
package io.github.lukehutch.quickunzip;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import io.github.lukehutch.quickunzip.Utils.AutoCloseableConcurrentQueue;
import io.github.lukehutch.quickunzip.Utils.AutoCloseableExecutorService;
import io.github.lukehutch.quickunzip.Utils.AutoCloseableFutureListWithCompletionBarrier;
import io.github.lukehutch.quickunzip.Utils.SingletonMap;

/** A fast unzipper for java that unzips a zipfile contents in parallel across multiple threads. */
public class QuickUnzip {

    /** Allocate 1.5x as many worker threads as CPU threads. */
    private static final int NUM_THREADS = Math.max(6,
            (int) Math.ceil(Runtime.getRuntime().availableProcessors() * 1.5f));

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Unzips the contents of the input zipfile into the requested output directory, creating the directory if it
     * doesn't exist.
     * 
     * @param inputZipfilePath
     *            The path to the zipfile to unzip.
     * @param outputDirPath
     *            The output directory to write the contents into. The contents of the zipfile root are decompressed
     *            directly into this directory, so this may create multiple new files or directories within this
     *            directory. However, if outputDirPath is null, instead the zipfile contents are unzipped into a
     *            directory in the same parent directory as the zipfile, and with the same name as the zipfile, but
     *            with the ".zip" or ".jar" extension removed (or with "-files" appended, if the zipfile does not
     *            have one of those extensions).
     * @param overwrite
     *            If true, overwrite existing files when unzipping.
     * @param verbose
     *            If true, show directory and file names as they are created.
     */
    public static void fastUnzip(final Path inputZipfilePath, final Path outputDirPath, final boolean overwrite,
            final boolean verbose) {
        // Check input zipfile name exists
        final var inputZipfile = inputZipfilePath.toFile();
        if (!inputZipfile.exists()) {
            System.err.println("Input zipfile not found: " + inputZipfile);
            System.exit(1);
        }

        // Create output dir
        Path unzipDirPath = null;
        try {
            // If output dir was not given, create a directory of the same name as the zipfile in the current dir
            if (outputDirPath == null) {
                // Unzip to a directory with ".zip" or ".jar" stripped from the zip file name,
                // or append "-files" if one of those extensions is not present
                final var fileLeafName = inputZipfile.getName();
                final var lastDotIdx = fileLeafName.lastIndexOf('.');
                final var filenameExtension = lastDotIdx > 0 ? fileLeafName.substring(lastDotIdx + 1) : "";
                final var unzipDirName = filenameExtension.equalsIgnoreCase("zip")
                        || filenameExtension.equalsIgnoreCase("jar") ? fileLeafName.substring(0, lastDotIdx)
                                : fileLeafName + "-files";
                // Unzip into a dir in the same parent directory as the zipfile
                unzipDirPath = new File(inputZipfile.getParentFile(), unzipDirName).toPath().toAbsolutePath();
            } else {
                unzipDirPath = outputDirPath.toAbsolutePath();
            }

            // Check output dir exists, and if not, call mkdirs
            final var unzipDirFile = unzipDirPath.toFile();
            if (!unzipDirFile.exists()) {
                final var mkdirsOk = unzipDirFile.mkdirs();
                if (!mkdirsOk) {
                    System.err.println("Could not create output directory: " + unzipDirFile);
                    System.exit(1);
                }
            }
        } catch (final Throwable e) {
            System.err.println("Could not create output directory: " + e);
            e.printStackTrace();
            System.exit(1);
        }

        if (verbose) {
            System.out.println("Unzipping " + inputZipfile + " to " + unzipDirPath);
        }
        final var unzipDirPathFinal = unzipDirPath;

        // Open the ZipFile and read all ZipEntries, ignoring directory entries
        final var zipEntries = new ArrayList<ZipEntry>();
        try (var zipFile = new ZipFile(inputZipfile)) {
            for (final var e = zipFile.entries(); e.hasMoreElements();) {
                zipEntries.add(e.nextElement());
            }
        } catch (final IOException e) {
            System.err.println("Could not read zipfile directory entries: " + e);
            System.exit(1);
        }

        // Singleton map indicating which directories were able to be successfully created (or already existed),
        // to avoid duplicating work calling mkdirs() multiple times for the same directories
        final var createdDirs = new SingletonMap<File, Boolean>() {
            @Override
            public Boolean newInstance(final File parentDir) throws Exception {
                var parentDirExists = parentDir.exists();
                if (!parentDirExists) {
                    parentDirExists = parentDir.mkdirs();
                    if (!parentDirExists) {
                        // Check one more time, if mkdirs failed, in case there were some existing
                        // symlinks putting the same dir on two physical paths, and another thread
                        // already created the dir. 
                        parentDirExists = parentDir.exists();
                    }
                    if (verbose) {
                        final String dirPathRelative = unzipDirPathFinal.relativize(parentDir.toPath()).toString()
                                + "/";
                        if (!parentDirExists) {
                            System.out.println(" Cannot create: " + dirPathRelative);
                        } else if (!parentDir.isDirectory()) {
                            // Can't overwrite a file with a directory 
                            System.out.println("Already exists: " + dirPathRelative);
                            parentDirExists = false;
                        } else {
                            System.out.println("      Creating: " + dirPathRelative);
                        }
                    }
                }
                return parentDirExists;
            }
        };

        // Iterate through ZipEntries, extracting in parallel
        try (final var openZipFiles = new AutoCloseableConcurrentQueue<ZipFile>();
                final var executor = new AutoCloseableExecutorService("QuickUnzip", NUM_THREADS);
                final var futures = new AutoCloseableFutureListWithCompletionBarrier(zipEntries.size())) {
            for (final var zipEntry : zipEntries) {
                futures.add(executor.submit(() -> {
                    final ThreadLocal<ZipFile> zipFileTL = ThreadLocal.withInitial(() -> {
                        try {
                            // Open one ZipFile instance per thread
                            final ZipFile zipFile = new ZipFile(inputZipfile);
                            openZipFiles.add(zipFile);
                            return zipFile;
                        } catch (final IOException e) {
                            // Should not happen unless zipfile was just barely deleted, since we opened it already
                            System.err.println("Could not open zipfile " + inputZipfile + " : " + e);
                            System.exit(1);
                        }
                        // Keep compiler happy
                        return null;
                    });
                    var entryName = zipEntry.getName();
                    while (entryName.startsWith("/")) {
                        // Strip leading "/" if present
                        entryName = entryName.substring(1);
                    }
                    try {
                        // Make sure we don't allow paths that use "../" to break out of the unzip root dir
                        final var entryPath = unzipDirPathFinal.resolve(entryName);
                        if (!entryPath.startsWith(unzipDirPathFinal)) {
                            if (verbose) {
                                System.out.println("      Bad path: " + entryName);
                            }
                        } else if (zipEntry.isDirectory()) {
                            // Recreate directory entries, so that empty directories are recreated 
                            createdDirs.getOrCreateSingleton(entryPath.toFile());
                        } else {
                            // Create parent directories if needed
                            final var entryFile = entryPath.toFile();
                            final var parentDir = entryFile.getParentFile();
                            final var parentDirExists = createdDirs.getOrCreateSingleton(parentDir);
                            if (parentDirExists) {
                                // Open ZipEntry as an InputStream
                                try (var inputStream = zipFileTL.get().getInputStream(zipEntry)) {
                                    if (overwrite) {
                                        if (verbose) {
                                            System.out.println("     Unzipping: " + entryName);
                                        }
                                        // Copy the contents of the ZipEntry InputStream to the output file,
                                        // overwriting existing files of the same name
                                        Files.copy(inputStream, entryPath, StandardCopyOption.REPLACE_EXISTING);
                                    } else {
                                        if (!entryFile.exists()) {
                                            if (verbose) {
                                                System.out.println("     Unzipping: " + entryName);
                                            }
                                            // Copy the contents of the ZipEntry InputStream to the output file
                                            Files.copy(inputStream, entryPath);
                                        } else if (verbose) {
                                            System.out.println("Already exists: " + entryName);
                                        }
                                    }
                                }
                            }
                        }
                    } catch (final InvalidPathException ex) {
                        if (verbose) {
                            System.out.println("  Invalid path: " + entryName);
                        }
                    }
                    // Return placeholder Void result for Future<Void>
                    return null;
                }));
            }
        }
        System.out.flush();
        System.err.flush();
    }

    // -------------------------------------------------------------------------------------------------------------

    public static void main(final String[] args) {
        final var unmatchedArgs = new ArrayList<String>();
        boolean overwrite = false;
        boolean verbose = true;
        for (final var arg : args) {
            if (arg.equals("-o")) {
                overwrite = true;
            } else if (arg.equals("-q")) {
                verbose = false;
            } else if (arg.startsWith("-")) {
                System.err.println("Unknown switch: " + arg);
                System.exit(1);
            } else {
                unmatchedArgs.add(arg);
            }
        }
        if (unmatchedArgs.size() != 1 && unmatchedArgs.size() != 2) {
            System.err.println(
                    "Syntax: java " + QuickUnzip.class.getName() + " [-o] [-q] zipfilename.zip [outputdir]");
            System.err.println(" Where:  -q => quiet");
            System.err.println("         -o => overwrite");
            System.exit(1);
        }
        fastUnzip(Paths.get(unmatchedArgs.get(0)),
                unmatchedArgs.size() == 2 ? Paths.get(unmatchedArgs.get(1)) : null, overwrite, verbose);
    }
}
