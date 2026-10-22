# sailpoint-alms-ui

A local web page for running [sailpoint-alms](https://github.com/mjagbeke/sailpoint-alms) sheets and drivers.

## Running it

1. Install the sailpoint-alms version the `pom.xml` names (6.1.0) into your local Maven repository, from its tag:
   ```powershell
   git -C ..\sailpoint-alms checkout v6.1.0
   mvn -f ..\sailpoint-alms\pom.xml install -DskipTests
   ```
2. Start the UI and open http://localhost:8080:
   ```powershell
   mvn spring-boot:run
   ```

The UI listens on 127.0.0.1 only. It refuses requests whose `Host` isn't localhost, and POST requests without its `X-ALMS-Request` header, so other web pages open in your browser can't use it.

## Credentials

The UI uses the credentials files in `~/.sailpoint-alms/credentials`: one SOPS-encrypted YAML file per tenant, holding `company`, `tenantType`, `clientId` and `clientSecret`. Create one with sailpoint-alms:

```powershell
java -cp sailpoint-alms.jar io.isc.runner.mainClass --set-credential $HOME\.sailpoint-alms\credentials\acme.yaml
```

Secrets are never typed into the page. The page only names a file, and the library decrypts it when it requests a token. To use another folder, start the UI with `--alms.credentials-dir=<folder>`.

## Single sheet

1. Choose the credentials file, the operation and the API version. **Download template** gives the operation's sheet columns.
2. Drop the sheet and any data files it names (such as an import's `File Path`). They're kept side by side, so a sheet names its data files without a folder.
3. **Run sheet**. The log shows as it runs, and the OUTPUT files the sheet writes can be downloaded when it ends.

## Driver

1. **Choose folder…** and pick the folder that holds the driver and its sheets. Sub-folders are kept.
2. The UI finds the driver (a CSV with `operation` and `pathToInput` columns) and checks every row first. It lists each row's tenant, API version and sheets, or why it will be skipped.
   - A sheet path is looked up from the driver's folder. A path written for another machine, such as `src/main/resources/demo/roles/RoleOps.csv`, matches the uploaded file whose path ends the same way.
   - A row's `credentialsFilePath` is looked up by file name in the credentials folder.
3. **Run driver**. Rows run in order, and rows that failed the check are skipped with an error.

Each new upload replaces the last one and its OUTPUT files. Uploads are kept in `alms.workspace-dir` (default: the system temp folder) and are deleted when the UI stops. Only one run happens at a time.
