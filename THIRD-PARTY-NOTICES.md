# Third-Party Notices

MCArgus itself is licensed under the MIT License (see `LICENSE`). The fat jar also
contains the following third-party components, each under its own license.

## junrar 8.1.2 — RAR extraction

- Project: https://github.com/junrar/junrar
- License: UnRAR License (reproduced below)

junrar is used **only to read and extract RAR archives**. In accordance with the
UnRAR License, it must not be used to develop a RAR (WinRAR) compatible archiver
or to re-create the RAR compression algorithm.

```text
 ******    *****   ******   UnRAR - free utility for RAR archives
 **   **  **   **  **   **  ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
 ******   *******  ******    License for use and distribution of
 **   **  **   **  **   **   ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
 **   **  **   **  **   **         FREE portable version
                                   ~~~~~~~~~~~~~~~~~~~~~

      The source code of UnRAR utility is freeware. This means:

   1. All copyrights to RAR and the utility UnRAR are exclusively
      owned by the author - Alexander Roshal.

   2. The UnRAR sources may be used in any software to handle RAR
      archives without limitations free of charge, but cannot be used
      to re-create the RAR compression algorithm, which is proprietary.
      Distribution of modified UnRAR sources in separate form or as a
      part of other software is permitted, provided that it is clearly
      stated in the documentation and source comments that the code may
      not be used to develop a RAR (WinRAR) compatible archiver.

   3. The UnRAR utility may be freely distributed. It is allowed
      to distribute UnRAR inside of other software packages.

   4. THE RAR ARCHIVER AND THE UnRAR UTILITY ARE DISTRIBUTED "AS IS".
      NO WARRANTY OF ANY KIND IS EXPRESSED OR IMPLIED.  YOU USE AT
      YOUR OWN RISK. THE AUTHOR WILL NOT BE LIABLE FOR DATA LOSS,
      DAMAGES, LOSS OF PROFITS OR ANY OTHER KIND OF LOSS WHILE USING
      OR MISUSING THIS SOFTWARE.

   5. Installing and using the UnRAR utility signifies acceptance of
      these terms and conditions of the license.

   6. If you don't agree with terms of the license you must remove
      UnRAR files from your storage devices and cease to use the
      utility.

      Thank you for your interest in RAR and UnRAR.


                                            Alexander L. Roshal
```

## Apache Commons Compress 1.28.0

- Project: https://commons.apache.org/proper/commons-compress/
- License: Apache License 2.0

Used for tar, tar.gz, tar.bz2, 7z and zip stream handling.

## XZ for Java 1.12

- Project: https://tukaani.org/xz/java.html
- License: public domain

Used as the xz/LZMA decoder backend for `tar.xz` and 7z.

## Apache Commons IO 2.20.0, Apache Commons Codec 1.19.0, Apache Commons Lang 3.18.0

- Project: https://commons.apache.org/
- License: Apache License 2.0

Transitive dependencies of Apache Commons Compress.

## SLF4J API 2.0.19

- Project: https://www.slf4j.org/
- License: MIT

## Logback Classic / Logback Core 1.6.6

- Project: https://logback.qos.ch/
- License: EPL-1.0 or LGPL-2.1

Logging backend bundled in the fat jar.
