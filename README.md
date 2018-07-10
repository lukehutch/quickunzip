# QuickUnzip
Fast parallel unzipper written in Java.

Unzips a zipfile across multiple threads, using one `ZipFile` instance per thread. (`ZipFile` objects have synchronized methods, so one instance is required per thread for parallel unzipping.)

This is probably about the fastest unzipper that it is possible to write using the standard Java `ZipFile` API.

Commandline syntax: 

```
java io.github.lukehutch.quickunzip.QuickUnzip [-o] [-q] zipfilename.zip [outputdir]

    Where:  -q => quiet
            -o => overwrite
```

If `outputdir` is not specified, the zipfile is extracted into a directory with the same name as the zipfile, but with the ".zip" or ".jar" extension removed, and this directory is created in the same directory as the zipfile.

If `outputdir` is specified, that directory is created if it doesn't exist, then all of the toplevel contents of the zipfile are extracted into that directory.
