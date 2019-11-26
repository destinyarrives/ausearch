package soar.edu.searchvisualization.model;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Writer;

import java.net.MalformedURLException;
import java.net.URL;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.Scanner;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.json.JSONArray;
import org.json.JSONObject;

import com.github.kevinsawicki.http.HttpRequest;
import soar.edu.searchvisualization.model.MavenPackage;
import soar.edu.searchvisualization.model.Query;
import soar.edu.searchvisualization.model.ResolvedFile;
import soar.edu.searchvisualization.model.Response;
import soar.edu.searchvisualization.model.SynchronizedData;
import soar.edu.searchvisualization.model.SynchronizedFeeder;
import soar.edu.searchvisualization.model.GithubToken;
import soar.edu.searchvisualization.utils.DirExplorer;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ParseProblemException;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.expr.Name;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.resolution.UnsolvedSymbolException;
import com.github.javaparser.resolution.declarations.ResolvedMethodDeclaration;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.model.resolution.TypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JarTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;

public class CodeSearch {


    // run multiple token
    // please make sure that the number of thread is equal with the number of tokens
    private static final int NUMBER_THREADS = 3;

    // parameter for the request
    private static final String PARAM_QUERY = "q"; //$NON-NLS-1$
    private static final String PARAM_PAGE = "page"; //$NON-NLS-1$
    private static final String PARAM_PER_PAGE = "per_page"; //$NON-NLS-1$

    // links from the response header
    private static final String META_REL = "rel"; //$NON-NLS-1$
    private static final String META_NEXT = "next"; //$NON-NLS-1$
    private static final String DELIM_LINKS = ","; //$NON-NLS-1$
    private static final String DELIM_LINK_PARAM = ";"; //$NON-NLS-1$

    // response code from github
    private static final int BAD_CREDENTIAL = 401;
    private static final int RESPONSE_OK = 200;
    private static final int ABUSE_RATE_LIMITS = 403;
    private static final int UNPROCESSABLE_ENTITY = 422;

    private static final long INFINITY = -1;
    private static long MAX_DATA = INFINITY;

    // folder location to save the downloaded files and jars
    private static String DATA_LOCATION = "src/main/java/soar/edu/searchvisualization/data/";
    private static final String JARS_LOCATION = "src/main/java/soar/edu/searchvisualization/jars/";
    private static final String ANDROID_JAR_LOCATION = "src/main/java/soar/edu/searchvisualization/android/";

    private static final String endpoint = "https://api.github.com/search/code";

    private static SynchronizedData synchronizedData = new SynchronizedData();
    private static SynchronizedFeeder synchronizedFeeder = new SynchronizedFeeder();

    private String query;

    public CodeSearch(String q) {
        this.query = q;
    }

    public ArrayList<ResolvedFile> run() {
        ArrayList<ResolvedFile> resolvedFiles = new ArrayList<ResolvedFile>();
        ArrayList<Query> queries = parseQueries(this.query);
        if (queries.size() > 0) {
            printQuery(queries);
            initUniqueFolderToSaveData(queries);
            resolvedFiles = processQuery(queries);
            for (int i = 0; i < resolvedFiles.size(); i++) {
                System.out.println();
                System.out.println("URL: " + resolvedFiles.get(i).getUrl());
                System.out.println("PathFile: " + resolvedFiles.get(i).getPathFile());
                System.out.println("Line: " + resolvedFiles.get(i).getLine());
                System.out.println("Column: " + resolvedFiles.get(i).getColumn());
                System.out.println("=== Snippet Codes ===");
                ArrayList<String> codes = getSnippetCode(resolvedFiles.get(i).getPathFile(),
                        resolvedFiles.get(i).getLine());
                for (int j = 0; j < codes.size(); j++) {
                    System.out.println(codes.get(j));
                }
            }
        }

        return resolvedFiles;
    }

    private static ArrayList<String> getSnippetCode(String pathFile, int desiredLine) {
        ArrayList<String> codes = new ArrayList<String>();

        BufferedReader reader;
        int i = 0;
        try {
            reader = new BufferedReader(new FileReader(pathFile));
            String line = reader.readLine();
            while (line != null) {
                i++;
                // System.out.println(line);

                if (i < (desiredLine + 5) && i > (desiredLine - 5)) {
                    System.out.println(line);
                    if (i == desiredLine) {
                        String method = "removeGpsStatusListener";
                        int column = line.indexOf(method);
                        System.out.println(column);
                        System.out.println(method.length());
                        String edited = line.substring(0, column) + "<mark>" + method + "</mark>" + line.substring(column + method.length());
                        codes.add(edited);
                    } else {
                        codes.add(line);
                    }
                }
                // read next line
                line = reader.readLine();
            }
            reader.close();
        } catch (IOException e) {
            e.printStackTrace();
        }

        return codes;
    }
    
    private static ArrayList<Query> parseQueries(String s) {
        ArrayList<Query> queries = new ArrayList<Query>();

        Query query = new Query();

        s = s.replace(" ", "");
        while (!s.equals("")) {
            int tagLocation = s.indexOf('#');
            int leftBracketLocation = s.indexOf('(');
            int rightBracketLocation = s.indexOf(')');
            if (tagLocation == -1 | leftBracketLocation == -1 || rightBracketLocation == -1
                    && tagLocation < leftBracketLocation && leftBracketLocation < rightBracketLocation) {
                System.out.println("Your query isn't accepted");
                System.out.println("Query Format: " + "method(argument_1, argument_2, ... , argument_n)");
                System.out.println("Example: "
                        + "android.app.Notification.Builder#addAction(int, java.lang.CharSequence, android.app.PendingIntent)");
                ;
                return new ArrayList<Query>();
            } else {
                String fullyQualifiedName = s.substring(0, tagLocation);
                String method = s.substring(tagLocation + 1, leftBracketLocation);
                String args = s.substring(leftBracketLocation + 1, rightBracketLocation);
                ArrayList<String> arguments = new ArrayList<String>();
                if (!args.equals("")) { // handle if no arguments
                    String[] arr = args.split(",");
                    for (int i = 0; i < arr.length; i++) {
                        arguments.add(arr[i]);
                    }
                }
                query.setFullyQualifiedName(fullyQualifiedName);
                query.setMethod(method);
                query.setArguments(arguments);
                queries.add(query);
                int andLocation = s.indexOf('&');
                if (andLocation == -1) {
                    s = "";
                } else {
                    s = s.substring(andLocation + 1);
                }
            }
        }

        return queries;
    }

    private static ArrayList<ResolvedFile> processQuery(ArrayList<Query> queries) {
        ArrayList<ResolvedFile> resolvedFiles = new ArrayList<ResolvedFile>();

        String query = prepareQuery(queries);

        int lower_bound, upper_bound, page, per_page_limit;
        lower_bound = 0;
        upper_bound = 384000;
        page = 1;
        per_page_limit = 30;

        Response response = handleCustomGithubRequest(query, lower_bound, upper_bound, page, per_page_limit);
        String nextUrlRequest;
        if (response.getTotalCount() == 0) {
            System.out.println("No item match with the query");
        } else {
            JSONArray item = response.getItem();
            nextUrlRequest = response.getNextUrlRequest();

            Queue<String> data = new LinkedList<>();
            for (int it = 0; it < item.length(); it++) {
                JSONObject instance = new JSONObject(item.get(it).toString());
                data.add(instance.getString("html_url"));
            }

            int id = 0;
            boolean isDownloaded, isResolved;
            ArrayList<String> urls = new ArrayList<>();
            CombinedTypeSolver combinedTypeSolver = new CombinedTypeSolver(new ReflectionTypeSolver(false),
                    new JavaParserTypeSolver(new File("src/main/java")));

            List<File> androidJars = findJarFiles(new File(ANDROID_JAR_LOCATION));
            for (File jar : androidJars) {
                try {
                    combinedTypeSolver.add(JarTypeSolver.getJarTypeSolver(jar.getPath()));
                } catch (IOException e) {
                    System.out.println("Can't add the jar: " + jar);
                    // TODO Auto-generated catch block
                    // e.printStackTrace();
                }
            }

            while (!data.isEmpty() && resolvedFiles.size() < 1) {
                if (data.size() == 1 && resolvedFiles.isEmpty()) {
                    response = handleGithubRequestWithUrl(nextUrlRequest);
                    item = response.getItem();
                    nextUrlRequest = response.getNextUrlRequest();
                    for (int it = 0; it < item.length(); it++) {
                        JSONObject instance = new JSONObject(item.get(it).toString());
                        data.add(instance.getString("html_url"));
                    }
                }
                String htmlUrl = data.remove();
                urls.add(htmlUrl);
                isDownloaded = downloadFile(htmlUrl, id);
                if (isDownloaded) {
                    ResolvedFile resolvedFile = resolveFile(id, queries, combinedTypeSolver);
                    resolvedFile.setUrl(htmlUrl);
                    if (!resolvedFile.getUrl().equals("")) {
                        isResolved = true;
                        resolvedFiles.add(resolvedFile);
                        System.out.println("File: " + urls.get(id));
                    }
                }
                id = id + 1;
            }
        }

        return resolvedFiles;

    }

    private static boolean downloadFile(String htmlUrl, int fileId) {
        // System.out.println("Download File Thread: " +
        // Thread.currentThread().toString());

        // convert html url to downloadable url
        // based on my own analysis
        String downloadableUrl = convertHTMLUrlToDownloadUrl(htmlUrl);

        // using it to make a unique name
        // replace java to txt for excluding from maven builder
        String fileName = fileId + ".txt";

        // System.out.println();
        // System.out.println("Downloading the file: " + (fileId));
        // System.out.println("HTML Url: " + htmlUrl);

        boolean finished = false;

        try {
            // download file from url
            URL url;
            url = new URL(downloadableUrl);
            ReadableByteChannel readableByteChannel = Channels.newChannel(url.openStream());
            String pathFile = new String(DATA_LOCATION + "files/" + fileName);
            System.out.println(downloadableUrl);
            System.out.println(pathFile);
            FileOutputStream fileOutputStream = new FileOutputStream(pathFile);
            fileOutputStream.getChannel().transferFrom(readableByteChannel, 0, Long.MAX_VALUE);
            fileOutputStream.close();
            finished = true;
        } catch (FileNotFoundException e) {
            System.out.println("Can't download the github file");
            System.out.println("File not found!");
        } catch (MalformedURLException e) {
            System.out.println("Malformed URL Exception while downloading!");
            e.printStackTrace();
        } catch (IOException e) {
            System.out.println("Can't save the downloaded file");
        }

        return finished;
    }

    private static ResolvedFile resolveFile(int fileId, ArrayList<Query> queries, CombinedTypeSolver solver) {
        String pathFile = new String(DATA_LOCATION + "files/" + fileId + ".txt");

        File file = new File(pathFile);

        ArrayList<String> snippetCodes = new ArrayList<String>();

        ResolvedFile resolvedFile = new ResolvedFile("", "", -1, -1, snippetCodes);

        try {
            System.out.println();
            printSign("=", file.toString().length() + 6);
            System.out.println("File: " + file);
            printSign("=", file.toString().length() + 6);

            List<String> addedJars = getNeededJars(file);
            for (int i = 0; i < addedJars.size(); i++) {
                try {
                    TypeSolver jarTypeSolver = JarTypeSolver.getJarTypeSolver(addedJars.get(i));
                    solver.add(jarTypeSolver);
                } catch (Exception e) {
                    System.out.println("Package corrupt!");
                    System.out.println("Corrupted Jars: " + addedJars.get(i));
                }
            }
            StaticJavaParser.getConfiguration().setSymbolResolver(new JavaSymbolSolver(solver));
            CompilationUnit cu;
            cu = StaticJavaParser.parse(file);

            ArrayList<Boolean> isMethodMatch = new ArrayList<Boolean>();
            ArrayList<Boolean> isResolved = new ArrayList<Boolean>();
            ArrayList<Boolean> isResolvedAndParameterMatch = new ArrayList<Boolean>();

            for (int i = 0; i < queries.size(); i++) {
                isMethodMatch.add(false);
                isResolved.add(false);
                isResolvedAndParameterMatch.add(false);
            }

            for (int i = 0; i < queries.size(); i++) {
                final int index = i;
                Query query = queries.get(index);
                cu.findAll(MethodCallExpr.class).forEach(mce -> {
                    if (mce.getName().toString().equals(query.getMethod())
                            && mce.getArguments().size() == query.getArguments().size()) {
                        isMethodMatch.set(index, true);
                        try {
                            ResolvedMethodDeclaration resolvedMethodDeclaration = mce.resolve();
                            String fullyQualifiedName = resolvedMethodDeclaration.getPackageName() + "."
                                    + resolvedMethodDeclaration.getClassName();
                            isResolved.set(index, true);
                            boolean isArgumentTypeMatch = true;
                            for (int j = 0; j < resolvedMethodDeclaration.getNumberOfParams(); j++) {
                                if (!query.getArguments().get(j)
                                        .equals(resolvedMethodDeclaration.getParam(j).describeType())) {
                                    isArgumentTypeMatch = false;
                                    break;
                                }
                            }
                            if (isArgumentTypeMatch
                                    && fullyQualifiedName.equals(queries.get(index).getFullyQualifiedName())) {
                                isResolvedAndParameterMatch.set(index, true);
                                resolvedFile.setPathFile(pathFile);
                                resolvedFile.setLine(mce.getBegin().get().line);
                                resolvedFile.setColumn(mce.getBegin().get().column);
                                resolvedFile.setCodes(getSnippetCode(resolvedFile.getPathFile(),
                                        resolvedFile.getLine()));
                            }
                        } catch (UnsolvedSymbolException unsolvedSymbolException) {
                            isResolved.set(index, false);
                        }
                    }
                });
            }

            boolean isSuccess = true;
            for (int i = 0; i < queries.size(); i++) {
                System.out.println("\nQuery " + (i + 1) + ": " + queries.get(i));
                if (isMethodMatch.get(i)) {
                    if (isResolved.get(i)) {
                        if (isResolvedAndParameterMatch.get(i)) {
                            System.out.println("Resolved and match argument type");
                        } else {
                            isSuccess = false;
                            System.out.println(
                                    "Resolved but argument type doesn't match :" + queries.get(i).getArguments());
                        }
                    } else {
                        isSuccess = false;
                        System.out.println("Can't resolve :" + queries.get(i).getMethod());
                    }
                } else {
                    isSuccess = false;
                    System.out.println("No method match :" + queries.get(i).getMethod());
                }
            }

            if (isSuccess) {
                System.out.println("SUCCESS");
            }

        } catch (ParseProblemException parseProblemException) {
            System.out.println("Parse Problem Exception in Type Resolution");
        } catch (RuntimeException runtimeException) {
            System.out.println("Runtime Exception in Type Resolution");
        } catch (IOException io) {
            System.out.println("IO Exception in Type Resolution");
        }

        return resolvedFile;

    }

    private static String prepareQuery(ArrayList<Query> queries) {
        String stringQuery = "";
        for (int i = 0; i < queries.size(); i++) {
            Query query = queries.get(i);
            stringQuery += query.getMethod();
            for (int j = 0; j < query.getArguments().size(); j++) {
                stringQuery += " " + query.getArguments().get(j);
            }
            if (i != queries.size() - 1)
                stringQuery += " ";
        }

        return stringQuery;
    }

    private static void printQuery(ArrayList<Query> queries) {
        System.out.println("============");
        System.out.println("Your Queries");
        System.out.println("============");
        for (int i = 0; i < queries.size(); i++) {
            System.out.println("Query " + (i + 1) + ": " + queries.get(i));
        }
    }

    private static void initUniqueFolderToSaveData(ArrayList<Query> queries) {

        String folderName = "";
        for (int i = 0; i < queries.size(); i++) {
            folderName += queries.get(i).getMethod();
            if (i != queries.size() - 1) {
                folderName += "&";
            }
        }

        File dataFolder = new File(DATA_LOCATION);
        if (!dataFolder.exists()) {
            dataFolder.mkdir();
        }

        DATA_LOCATION = DATA_LOCATION + folderName + "/";

        File exactFolder = new File(DATA_LOCATION);
        if (!exactFolder.exists()) {
            exactFolder.mkdir();
        }

        File files = new File(DATA_LOCATION + "files/");
        if (!files.exists()) {
            files.mkdir();
        }

        File jarFolder = new File(JARS_LOCATION);
        if (!jarFolder.exists()) {
            jarFolder.mkdir();
        }

    }

    /**
     * Convert github html url to download url input:
     * https://github.com/shuchen007/ThinkInJavaMaven/blob/85b402a81fc0b3f2f039b34c23be416322ef14b4/src/main/java/sikaiClient/IScyxKtService.java
     * output:
     * https://raw.githubusercontent.com/shuchen007/ThinkInJavaMaven/85b402a81fc0b3f2f039b34c23be416322ef14b4/src/main/java/sikaiClient/IScyxKtService.java
     */
    private static String convertHTMLUrlToDownloadUrl(String html_url) {
        String[] parts = html_url.split("/");
        String download_url = "https://raw.githubusercontent.com/";
        int l = parts.length;
        for (int i = 0; i < l; i++) {
            if (i >= 3) {
                if (i != 5) {
                    if (i != l - 1) {
                        download_url = download_url.concat(parts[i] + '/');
                    } else {
                        download_url = download_url.concat(parts[i]);
                    }
                }
            }
        }

        return download_url;
    }

    public static class URLRunnable implements Runnable {
        private final String url;

        URLRunnable(String query, int lower_bound, int upper_bound, int page, int per_page_limit) {
            upper_bound++;
            lower_bound--;
            String size = lower_bound + ".." + upper_bound; // lower_bound < size < upper_bound
            this.url = endpoint + "?" + PARAM_QUERY + "=" + query + "+in:file+language:java+extension:java+size:" + size
                    + "&" + PARAM_PAGE + "=" + page + "&" + PARAM_PER_PAGE + "=" + per_page_limit;
        }

        @Override
        public void run() {
            Response response = handleGithubRequestWithUrl(url);
            JSONArray item = response.getItem();
            // System.out.println("Request: " + response.getUrlRequest());
            // System.out.println("Number items: " + item.length());
            synchronizedData.addArray(item);
        }
    }

    private static Response handleGithubRequestWithUrl(String url) {

        boolean response_ok = false;
        Response response = new Response();
        int responseCode;

        // encode the space into %20
        url = url.replace(" ", "%20");
        GithubToken token = synchronizedFeeder.getAvailableGithubToken();

        do {
            HttpRequest request = HttpRequest.get(url, false).authorization("token " + token.getToken());
            System.out.println();
            System.out.println("Request: " + request);
            System.out.println("Token: " + token);
            System.out.println("Thread: " + Thread.currentThread().toString());

            // handle response
            responseCode = request.code();
            if (responseCode == RESPONSE_OK) {
                // System.out.println("Header: " + request.headers());
                response.setCode(responseCode);
                JSONObject body = new JSONObject(request.body());
                response.setTotalCount(body.getInt("total_count"));
                response.setItem(body.getJSONArray("items"));
                response.setUrlRequest(request.toString());
                response.setNextUrlRequest(getNextLinkFromResponse(request.header("Link")));
                response_ok = true;
            } else if (responseCode == BAD_CREDENTIAL) {
                System.out.println("Authorization problem");
                System.out.println("Please read the readme file!");
                System.out.println("Github message: " + (new JSONObject(request.body())).getString("message"));
                System.exit(-1);
            } else if (responseCode == ABUSE_RATE_LIMITS) {
                System.out.println("Abuse Rate Limits");
                // retry current progress after wait for a minute
                String retryAfter = request.header("Retry-After");
                try {
                    int sleepTime = 0; // wait for a while
                    if (retryAfter.isEmpty()) {
                        sleepTime = 1;
                    } else {
                        sleepTime = new Integer(retryAfter).intValue();
                    }
                    System.out.println("Retry-After: " + sleepTime);
                    TimeUnit.SECONDS.sleep(sleepTime);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            } else if (responseCode == UNPROCESSABLE_ENTITY) {
                System.out.println("Response Code: " + responseCode);
                System.out.println("Unprocessbale Entity: only the first 1000 search results are available");
                System.out.println("See the documentation here: https://developer.github.com/v3/search/");
            } else {
                System.out.println("Response Code: " + responseCode);
                System.out.println("Response Body: " + request.body());
                System.out.println("Response Headers: " + request.headers());
                System.exit(-1);
            }

        } while (!response_ok && responseCode != UNPROCESSABLE_ENTITY);

        System.out.println("--- " + Thread.currentThread() + " END ");
        synchronizedFeeder.releaseToken(token);

        return response;
    }

    private static Response handleCustomGithubRequest(String query, int lower_bound, int upper_bound, int page,
            int per_page_limit) {
        // The size range is exclusive
        upper_bound++;
        lower_bound--;
        String size = lower_bound + ".." + upper_bound; // lower_bound < size < upper_bound

        String url;
        Response response = new Response();

        url = endpoint + "?" + PARAM_QUERY + "=" + query + "+in:file+language:java+extension:java+size:" + size + "&"
                + PARAM_PAGE + "=" + page + "&" + PARAM_PER_PAGE + "=" + per_page_limit;
        response = handleGithubRequestWithUrl(url);

        return response;
    }

    private static String getNextLinkFromResponse(String linkHeader) {

        String next = null;

        if (linkHeader != null) {
            String[] links = linkHeader.split(DELIM_LINKS);
            for (String link : links) {
                String[] segments = link.split(DELIM_LINK_PARAM);
                if (segments.length < 2)
                    continue;

                String linkPart = segments[0].trim();
                if (!linkPart.startsWith("<") || !linkPart.endsWith(">")) //$NON-NLS-1$ //$NON-NLS-2$
                    continue;
                linkPart = linkPart.substring(1, linkPart.length() - 1);

                for (int i = 1; i < segments.length; i++) {
                    String[] rel = segments[i].trim().split("="); //$NON-NLS-1$
                    if (rel.length < 2 || !META_REL.equals(rel[0]))
                        continue;

                    String relValue = rel[1];
                    if (relValue.startsWith("\"") && relValue.endsWith("\"")) //$NON-NLS-1$ //$NON-NLS-2$
                        relValue = relValue.substring(1, relValue.length() - 1);

                    if (META_NEXT.equals(relValue))
                        next = linkPart;
                }
            }
        }
        return next;
    }

    private static void printSign(String s, int x) {
        for (int i = 0; i < x; i++) {
            System.out.print(s);
        }
        System.out.println();
    }

    private static List<File> findJarFiles(File src) {
        List<File> files = new LinkedList<File>();
        new DirExplorer((level, path, file) -> path.endsWith(".jar"), (level, path, file) -> {
            files.add(file);
        }).explore(src);

        return files;
    }

    private static List<String> getNeededJars(File file) {
        List<String> jarsPath = new ArrayList<String>();
        TypeSolver typeSolver = new CombinedTypeSolver(new ReflectionTypeSolver(false),
                new JavaParserTypeSolver(new File("src/main/java")));
        StaticJavaParser.getConfiguration().setSymbolResolver(new JavaSymbolSolver(typeSolver));

        // list of specific package imported
        List<String> importedPackages = new ArrayList<String>();
        try {
            CompilationUnit cu = StaticJavaParser.parse(file);
            cu.findAll(Name.class).forEach(mce -> {
                String[] names = mce.toString().split("[.]");
                if (names.length >= 2) { // filter some wrong detected import like Override, SupressWarning
                    if (importedPackages.isEmpty()) {
                        importedPackages.add(mce.toString());
                    } else {
                        boolean isAlreadyDefined = false;
                        for (int i = 0; i < importedPackages.size(); i++) {
                            if (importedPackages.get(i).contains(mce.toString())) {
                                isAlreadyDefined = true;
                                break;
                            }
                        }
                        if (!isAlreadyDefined) {
                            importedPackages.add(mce.toString());
                        }
                    }
                }
            });
        } catch (FileNotFoundException e) {
            System.out.println("EXCEPTION");
            System.out.println("File not found!");
        } catch (ParseProblemException parseException) {
            return jarsPath;
        }

        // System.out.println();
        // System.out.println("=== Imported Packages ==");
        // for (int i = 0; i < importedPackages.size(); i++) {
        // System.out.println(importedPackages.get(i));
        // }

        // filter importedPackages
        // remove the project package and java predefined package
        List<String> neededPackages = new ArrayList<String>();
        if (importedPackages.size() > 0) {
            String qualifiedName = importedPackages.get(0);
            String[] names = qualifiedName.split("[.]");
            String projectPackage = names[0].toString();
            for (int i = 1; i < importedPackages.size(); i++) { // the first package is skipped
                qualifiedName = importedPackages.get(i);
                names = qualifiedName.split("[.]");
                String basePackage = names[0];
                if (!basePackage.equals(projectPackage) && !basePackage.equals("java") && !basePackage.equals("javax")
                        && !basePackage.equals("Override")) {
                    neededPackages.add(importedPackages.get(i));
                }
            }
        }

        // System.out.println();
        // System.out.println("=== Needed Packages ==");
        // for (int i = 0; i < neededPackages.size(); i++) {
        // System.out.println(neededPackages.get(i));
        // }

        List<MavenPackage> mavenPackages = new ArrayList<MavenPackage>();

        // get the groupId and artifactId from the package qualified name
        for (int i = 0; i < neededPackages.size(); i++) {
            String qualifiedName = neededPackages.get(i);
            MavenPackage mavenPackage = getMavenPackageArtifact(qualifiedName);

            if (!mavenPackage.getId().equals("")) { // handle if the maven package is not exist
                // filter if the package is used before
                boolean isAlreadyUsed = false;
                for (int j = 0; j < mavenPackages.size(); j++) {
                    MavenPackage usedPackage = mavenPackages.get(j);
                    if (mavenPackage.getGroupId().equals(usedPackage.getGroupId())
                            && mavenPackage.getArtifactId().equals(usedPackage.getArtifactId())) {
                        isAlreadyUsed = true;
                    }
                }
                if (!isAlreadyUsed) {
                    mavenPackages.add(mavenPackage);
                }
            }
        }

        // System.out.println();
        // System.out.println("=== Maven Packages ==");
        // for (int i = 0; i < mavenPackages.size(); i++) {
        // System.out.println("GroupID: " + mavenPackages.get(i).getGroupId() + " -
        // ArtifactID: "
        // + mavenPackages.get(i).getArtifactId());
        // }

        // System.out.println();
        // System.out.println("=== Downloading Packages ==");
        for (int i = 0; i < mavenPackages.size(); i++) {
            String pathToJar = downloadMavenJar(mavenPackages.get(i).getGroupId(),
                    mavenPackages.get(i).getArtifactId());
            if (!pathToJar.equals("")) {
                // System.out.println("Downloaded: " + pathToJar);
                jarsPath.add(pathToJar);
            }
        }

        return jarsPath;
    }

    // download the latest package by groupId and artifactId
    private static String downloadMavenJar(String groupId, String artifactId) {
        String path = JARS_LOCATION + artifactId + "-latest.jar";
        String url = "http://repository.sonatype.org/service/local/artifact/maven/redirect?r=central-proxy&g=" + groupId
                + "&a=" + artifactId + "&v=LATEST";
        // System.out.println("URL: " + url);
        File jarFile = new File(path);

        if (!jarFile.exists()) {
            // Equivalent command conversion for Java execution
            String[] command = { "curl", "-L", url, "-o", path };

            ProcessBuilder process = new ProcessBuilder(command);
            Process p;
            try {
                p = process.start();
                BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()));
                StringBuilder builder = new StringBuilder();
                String line = null;
                while ((line = reader.readLine()) != null) {
                    builder.append(line);
                    builder.append(System.getProperty("line.separator"));
                }
                String result = builder.toString();
                System.out.print(result);

            } catch (IOException e) {
                System.out.print("error");
                e.printStackTrace();
            }
        }

        return path;

    }

    private static MavenPackage getMavenPackageArtifact(String qualifiedName) {

        MavenPackage mavenPackageName = new MavenPackage();

        String url = "https://search.maven.org/solrsearch/select?q=fc:" + qualifiedName + "&wt=json";

        HttpRequest request = HttpRequest.get(url, false);

        // handle response
        int responseCode = request.code();
        if (responseCode == RESPONSE_OK) {
            JSONObject body = new JSONObject(request.body());
            JSONObject response = body.getJSONObject("response");
            int numFound = response.getInt("numFound");
            JSONArray mavenPackages = response.getJSONArray("docs");
            if (numFound > 0) {
                mavenPackageName.setId(mavenPackages.getJSONObject(0).getString("id")); // set the id
                mavenPackageName.setGroupId(mavenPackages.getJSONObject(0).getString("g")); // set the first group id
                mavenPackageName.setArtifactId(mavenPackages.getJSONObject(0).getString("a")); // set the first artifact
                                                                                               // id
                mavenPackageName.setVersion(mavenPackages.getJSONObject(0).getString("v")); // set the first version id
            }
        } else {
            System.out.println("Response Code: " + responseCode);
            System.out.println("Response Body: " + request.body());
            System.out.println("Response Headers: " + request.headers());
        }

        return mavenPackageName;
    }
}