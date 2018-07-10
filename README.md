# QuickUnzip
Fast parallel unzipper written in Java.

Unzips a zipfile across multiple threads, using one `ZipFile` instance per thread. (`ZipFile` objects have synchronized methods, so one instance is required per thread for parallel unzipping.)

QuickUnzip is probably about the fastest unzipper that it is possible to write using the standard Java `ZipFile` API -- it is twice as fast as InfoZip when unzipping the Eclipse Java development distribution zipfile (Core i7-4702HQ CPU @ 2.20GHz, 4 cores / 8 threads, with SSD).

Some care was taken to ensure that unzipping is safe (e.g. zipfile paths containing `../` cannot escape the output directory, and `/` is stripped from the beginning of zipfile paths to relativize them).

Commandline syntax: 

```
java io.github.lukehutch.quickunzip.QuickUnzip [-o] [-q] zipfilename.zip [outputdir]

    Where:  -q => quiet
            -o => overwrite
```

If `outputdir` is not specified, the zipfile is extracted into a directory with the same name as the zipfile, but with the ".zip" or ".jar" extension removed, and this directory is created in the same directory as the zipfile.

If `outputdir` is specified, that directory is created if it doesn't exist, then all of the toplevel contents of the zipfile are extracted into that directory.
