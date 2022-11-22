# Datimprint

Jordial's Datimprint™ software for data statistics, fingerprint, and verification.

Visit the [Datimprint home page](https://www.jordial.com/software/datimprint/) for more detailed information and documentation. The source code is publicly available via the [GitHub project](https://github.com/jordial/datimprint), where you can report [issues](https://github.com/jordial/datimprint/issues) or participate in [discussions](https://github.com/jordial/datimprint/discussions) about the program.

## Overview

The Datimprint program helps you verify that all of your data is intact—every single bit—by generating snapshots at single points in time and later comparing them with current data. Datimprint produces files containing “fingerprints” of each file. These “data imprints” take up just a tiny fraction of the size of the data itself, but practically guarantee that any subsequent changes to the data can be detected.

* Ensure that your backup is 100% identical to the original.
* Monitor your data for data degradation or “bit rot”.
* Maintain snapshot “imprints” of your data at a certain point in time for later comparison.


## Datimprint CLI

The Datimprint Command-Line Interface (CLI) is the program that performs all datimprint functionality. _Datimprint CLI requires Java to be installed on your machine, with the correct `JAVA_HOME` environment variable set._ You must download the [latest Datimprint CLI version from Maven](https://search.maven.org/search?q=g:com.jordial.datimprint%20AND%20a:datimprint-cli): select the ↓ download icon next to the latest version, and then choose either **`bin.tar.xz`** or **`bin.zip`**. The archive will contain a self-contained executable script for Linux, an executable file for Windows, and an executable JAR for all other Java systems.

Read the complete [installation instructions](https://www.jordial.com/software/datimprint/install) to ensure Datimprint CLI is installed correctly on your system. You can check which version you have installed using the `--version` switch.

## Generate Data Imprint
Generate an imprint, which will be stored in a [datim file](https://www.jordial.com/software/datimprint/overview#datim), using the `generate` command. Include the path to the directory tree, and specify where you would like to store the imprint using the `--output` or `-o` option.

```powershell
datimprint generate C:\data --output C:\imprints\data-2022-11-12.datim
```

## Check Data Imprint
Check the current contents of any data tree against a [datim file](https://www.jordial.com/software/datimprint/overview#datim) file using the `check` command. Include the path to the directory tree containing the files and directories to check, and specify which imprint you would like to check the data against using the `--imprint` or `-i` option. The data being verified might be a backup, or it might be the original data, to detect data degradation.

```powershell
datimprint check C:\backup\data --imprint C:\imprints\data-2022-11-12.datim
```
**The `check` command only checks the paths that are included in the imprint.** If a file or a directory is missing, this will be noted in the report, but any new files (i.e. those not listed in the imprint) will be ignored.
