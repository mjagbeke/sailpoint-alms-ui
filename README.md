# sailpoint-alms-ui

A local web page for running [sailpoint-alms](https://github.com/mjagbeke/sailpoint-alms) sheets and drivers. It works on your files where they are, the same way the command line does.

## Running it

1. Install the sailpoint-alms version the `pom.xml` names (7.0.0) into your local Maven repository, from its tag:
   ```powershell
   git -C ..\sailpoint-alms checkout v7.0.0
   mvn -f ..\sailpoint-alms\pom.xml install -DskipTests
   ```
2. Start the UI, telling it which folder holds your drivers and sheets, then open http://localhost:8080:
   ```powershell
   $env:ALMS_WORKDIR = "C:\work"
   mvn spring-boot:run
   ```
   Without `ALMS_WORKDIR`, the work folder is your home folder.

The UI listens on 127.0.0.1 only. It refuses requests whose `Host` isn't localhost, and POST requests without its `X-ALMS-Request` header, so other web pages open in your browser can't use it. It lists and reads files only inside the work folder.

## Tenants

The UI uses the same credentials folder as the command line, `~/.sailpoint-alms/credentials`, with one SOPS-encrypted `<tenant>.yaml` per tenant. Create one with sailpoint-alms:

```powershell
java -jar sailpoint-alms-7.0.0-jar-with-dependencies.jar --set-credential acme --age age1…
```

Secrets are never typed into the page. The page only names a tenant, and the library decrypts its file when it requests a token. To use another folder, set `ALMS_CREDENTIALSDIR`.

## Single sheet

1. Choose the tenant, the operation and the API version. **Download template** gives the operation's sheet columns.
2. **Choose sheet…** and pick the sheet in the work folder.
3. **Run sheet**. The log shows as it runs. Data files the sheet names (such as an import's `File Path`) are found next to it, OUTPUT files are written next to it, and the page links to them when the run ends.

## Driver

1. **Choose driver…** and pick a driver in the work folder. Files with `operation` and `pathToInput` columns are marked as drivers; only those can be chosen.
2. The UI checks every row before anything runs, and lists each row's tenant, API version and sheets, or why it will be skipped.
   - A sheet path is used as written if it's absolute. Otherwise it's looked up from the driver's folder, then from each folder above it up to the work folder, so paths written from a repository's root (such as `src/main/resources/demo/fixtures/people-source.csv`) are found.
   - A row's `tenant` names its credentials file in the credentials folder.
3. **Run driver**. Rows run in order. If some rows will be skipped, the button says how many will run and asks before starting, because skipped setup or teardown rows can make the others fail or leave objects in the tenant.

Only one run happens at a time. Only the OUTPUT files a run wrote can be downloaded through the page.
