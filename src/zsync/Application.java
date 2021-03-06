package zsync;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;

public class Application {

    private Options createCommandLineOptions() {

        Options options = new Options();

        options.addOption("h", "help", false, "print this help and exit");

        options.addOption("s", "hostname", true, "FTP hostname");
        options.addOption("u", "username", true, "FTP username");

        Option option = new Option("p", "password", true, "FTP password");
        option.setRequired(true);
        options.addOption(option);

        option = new Option("l", "local-root", true, "local root");
        option.setRequired(true);
        options.addOption(option);

        option = new Option("r", "remote-root", true, "remote root");
        option.setRequired(true);
        options.addOption(option);
        
        options.addOption("e", "exclude-path", true, "exclude path from the synchronization");
        options.addOption("d", "datasets-options", true, "data sets allocation parameters");
        options.addOption("x", "index-file", true, "index file");
        options.addOption("v", "verbose", false, "verbose");
        options.addOption("i", "update-index", false, "update index");
        options.addOption("o", "upload", false, "upload");

        return options;
    }
    
    private boolean helpSpecified;
    private String hostname;
    private String username;
    private String password;
    private String localRootPath;
    private String remoteRootPath;
    private List<String> excludePaths = new ArrayList<String>();
    private String indexFileName;
    private boolean verboseSpecified;
    private boolean updateIndexSpecified;
    private boolean uploadSpecified;
    private Map<String, String> datasetsOptions = new HashMap<String, String>();
    
    private void processComandLineOptions(String commandLineArguments[]) throws ParseException, FileNotFoundException, IOException {
        CommandLineParser parser = new DefaultParser();

        Options options = new Options();
        options.addOption("h", "help", false, "print this help and exit");

        CommandLine commandLine = parser.parse(options, commandLineArguments, true);

        if (commandLine.hasOption("help")) {
        	helpSpecified = true;
            return;
        }

        commandLine = parser.parse(createCommandLineOptions(), commandLineArguments);

        hostname = null;
        if (commandLine.hasOption("hostname")) {
            hostname = commandLine.getOptionValue("hostname");
        } else {
            hostname = "localhost";
        }

        username = null;
        if (commandLine.hasOption("username")) {
            username = commandLine.getOptionValue("username");
        } else {
            username = System.getProperty("user.name");
        }

        password = commandLine.getOptionValue("password");
        localRootPath = commandLine.getOptionValue("local-root");
        remoteRootPath = commandLine.getOptionValue("remote-root");
        
        String[] excludePaths = commandLine.getOptionValues("exclude-path");
        if (excludePaths != null) {
        	this.excludePaths = Arrays.asList(excludePaths);        	
        }
        
        if (commandLine.hasOption("datasets-options")) {
            try (BufferedReader reader = new BufferedReader(new FileReader(commandLine.getOptionValue("datasets-options")))) {
                String line;
                while ((line = reader.readLine()) != null) {
                	int blankIndex = line.indexOf(" ");
                	String datasetName = line.substring(0, blankIndex);
                	String datasetOptions = line.substring(blankIndex + 1);
                	datasetsOptions.put(datasetName, datasetOptions);
                }
            }        	
        }

        indexFileName = null;
        if (commandLine.hasOption("index-file")) {
            indexFileName = commandLine.getOptionValue("index-file");
        } else {
            indexFileName = "zsync.xml";
        }

        verboseSpecified = commandLine.hasOption("verbose");
        uploadSpecified = commandLine.hasOption("upload");
        updateIndexSpecified = commandLine.hasOption("update-index");
    }
    
    public String filePathToDatasetName(String filePath) {
        String directories[] = filePath.split("\\" + File.separator);

        String datasetName = "";
        for (int directoryIndex = 0; directoryIndex < directories.length - 1; directoryIndex++) {
            String directory = directories[directoryIndex];
            if (directoryIndex > 0) {
                datasetName += ".";
            }
            datasetName += String.format("%.8s", directory);
        }

        String memberName;
        String fileName = directories[directories.length - 1];
        int dotIndex = fileName.lastIndexOf(".");
        if (dotIndex == -1) {
            memberName = fileName;
        } else {
            memberName = String.format("%.8s", fileName.substring(0, dotIndex));
        }
        
        if (datasetName.isEmpty()) {
            datasetName = remoteRootPath;
        } else {
            datasetName = String.format("%s.%s", remoteRootPath, datasetName);
        }
        
        datasetName = String.format("%s(%s)", datasetName, memberName);
        datasetName = datasetName.toUpperCase();
        
        return datasetName;
    }
    
    private static String getDatasetNameWithoutMember(String datasetName) {
        int bracketIndex = datasetName.lastIndexOf("(");
        if (bracketIndex != -1) {
            datasetName = datasetName.substring(0, bracketIndex);
        }
        return datasetName;
    }
    
    private FTPClient clientFTP = new FTPClient();
    private Index index;
    boolean filesChanged;
    
    private void synchronizeFiles() throws Exception {
        if (verboseSpecified) {
            System.out.format("processing '%s' directory files%n", localRootPath);
        }
        List<File> files = Files.walk(localRootPath);
        List<File> changedFiles = new ArrayList<File>();
        for (File file : files) {
            if (!file.isFile()) {
                continue;
            }

            String filePath = file.getPath().substring(localRootPath.length() + 1);
            
            boolean excludeFile = false;
            for (String excludePath : excludePaths) {
            	if (filePath.startsWith(excludePath)) {
            		excludeFile = true;
            		break;
            	}
            }
            if (excludeFile) {
            	continue;
            }
            
            IndexEntry indexEntry = index.getEntryMap().get(filePath);
            if (indexEntry == null || file.lastModified() > indexEntry.getLastModified()) {
                changedFiles.add(file);
            }
        }
        
        List<String> removedFilePaths = new ArrayList<String>();
        for (Map.Entry<String, IndexEntry> entry : index.getEntryMap().entrySet()) {
            
            boolean fileFound = (new File(localRootPath, entry.getKey())).exists();
            
            if (!fileFound) {
                removedFilePaths.add(entry.getKey());
            }
        }

        filesChanged = changedFiles.size() > 0 || removedFilePaths.size() > 0;
        if (!filesChanged) {
            System.out.println("no files has been added or changed or removed");
            return;
        }
        
        if (verboseSpecified) {
            System.out.format("connecting to '%s' host%n", hostname);
        }
        clientFTP.connect(hostname);
        if (verboseSpecified) {
            System.out.format("logging in '%s' host as '%s' user%n", hostname, username);
        }
        clientFTP.login(username, password);

        for (File file : changedFiles) {

            String filePath = file.getPath().substring(localRootPath.length() + 1);

            if (uploadSpecified) {
                String datasetName = filePathToDatasetName(filePath);
                String directoryName = getDatasetNameWithoutMember(datasetName);
                System.out.format("uploading '%s' file to '%s' data set%n", filePath, directoryName);
                BufferedInputStream fileStream = new BufferedInputStream(new FileInputStream(file));
                if (!clientFTP.storeDataset(datasetName, fileStream)) {
                    String replyString = clientFTP.getReplyString();
                    if (replyString.indexOf("requests a nonexistent partitioned data set") != -1) {
                        System.out.println("upload has failed because data set does not exist");
                        
                        String relativeDirectoryName = null;
                        if (directoryName.length() > remoteRootPath.length() && datasetsOptions.containsKey(relativeDirectoryName)) {
                        	relativeDirectoryName = directoryName.substring(remoteRootPath.length() + 1);                        	
                        }
                        
                        if (relativeDirectoryName != null && datasetsOptions.containsKey(relativeDirectoryName)) {
                        	String datasetParameters = datasetsOptions.get(relativeDirectoryName);
                        	clientFTP.site(datasetParameters);
                        	System.out.format("creating '%s' data set with parameters '%s'%n", directoryName, datasetParameters);
                        } else {
                        	System.out.format("creating '%s' data set%n", directoryName);                        	
                        }
                        clientFTP.createPDS(directoryName);
                        
                        System.out.format("uploading '%s' file to '%s' data set%n", filePath, directoryName);
                        clientFTP.storeDataset(datasetName, fileStream);
                    } else {
                        throw new Exception(replyString);
                    }
                }
            } else {
                System.out.format("'%s' file changed on %s%n", filePath,
                        new SimpleDateFormat("dd.MM.yyyy HH:mm:ss").format(new Date(file.lastModified())));
            }

            IndexEntry indexEntry = new IndexEntry();
            indexEntry.setPath(filePath);
            indexEntry.setLastModified(file.lastModified());
            index.getEntryMap().put(filePath, indexEntry);
        }
        
        for (String filePath : removedFilePaths) {
            String datasetName = filePathToDatasetName(filePath);
            String directoryName = getDatasetNameWithoutMember(datasetName);
            System.out.format("deleting '%s' file from '%s' data set%n", filePath, directoryName);
            if (!clientFTP.deleteDataset(datasetName)) {
                throw new Exception(clientFTP.getReplyString());
            }
            index.getEntryMap().remove(filePath);
        }

        if (clientFTP.isConnected()) {
            if (verboseSpecified) {
                System.out.format("logging out '%s' host%n", hostname);
            }
            clientFTP.logout();
        }
    }

    public void run(String arguments[]) throws Exception {
        
        processComandLineOptions(arguments);
        
        if (helpSpecified) {
        	HelpFormatter formatter = new HelpFormatter();
            formatter.setOptionComparator(null);
            formatter.printHelp("zsync", createCommandLineOptions());
            return;
        }

        /* load file index */
        File indexFile = new File(indexFileName);
        if (indexFile.exists()) {
            if (verboseSpecified) {
                System.out.format("loading index from '%s' file%n", indexFileName);
            }
            index = Index.loadFromFile(indexFile);
        } else {
            index = new Index();
        }
        
        synchronizeFiles();

        /* save file index if neccesary */
        if (!updateIndexSpecified && filesChanged) {
            if (verboseSpecified) {
                System.out.format("saving index to '%s' file%n", indexFileName);
            }
            index.saveToFile(indexFile);
        }
    }

    public static void main(String arguments[]) {

        try {
            (new Application()).run(arguments);
        } catch (Exception error) {
            System.out.println(error.getMessage());
        }
    }
}
