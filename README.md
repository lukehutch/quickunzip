# quickunzip
Fast parallel unzipper written in Java.

Unzips file across multiple threads, using one `ZipFile` instance per thread. (`ZipFile` objects have synchronized methods, so you one instance is required per thread for parallel unzipping.)

This is probably about the fastest unzipper that it is possible to write using the standard Java `ZipFile` API.
