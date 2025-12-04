package com.detectionengine.app.service;

import com.detectionengine.app.loader.RegistryLoader;
import com.detectionengine.app.model.DetectionResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
public class DetectionService {

    private final RegistryLoader loader;
    private ThreadLocal<String> detectedProjectType = new ThreadLocal<>();
    private ThreadLocal<Map<String, String>> fileContentCache = ThreadLocal.withInitial(HashMap::new);

    public DetectionService(RegistryLoader loader) {
        this.loader = loader;
    }

    public DetectionResult detect(String projectRoot) {
        try {
            DetectionResult result = new DetectionResult();
            JsonNode registry = loader.get();
            fileContentCache.get().clear(); // Clear cache for new detection

            // First pass: Detect project type
            detectedProjectType.set(detectProjectType(projectRoot));

            // Collect all files first to avoid stream issues
            List<Path> filesToScan;
            try (Stream<Path> files = Files.walk(Paths.get(projectRoot), 6) // Increase depth to 6 levels
                .filter(Files::isRegularFile)
                .filter(path -> !isIgnoredPath(path))) { // Skip node_modules, target, etc.

                filesToScan = files.collect(Collectors.toList());
            } catch (IOException e) {
                throw new RuntimeException("Failed to scan project", e);
            }

            // Debug: Print detected project type and file count
            System.out.println("[DEBUG] Detected project type: " + detectedProjectType.get());
            System.out.println("[DEBUG] Total files to scan: " + filesToScan.size());
            System.out.println("[DEBUG] Looking for pom.xml...");
            filesToScan.stream()
                .filter(p -> p.toString().endsWith("pom.xml"))
                .forEach(p -> System.out.println("[DEBUG] Found pom.xml at: " + p));

            // Process files
            filesToScan.forEach(path -> applyRules(path, registry, result));

            return result;
        } finally {
            // Clean up ThreadLocal to prevent memory leaks
            fileContentCache.get().clear();
            detectedProjectType.remove();
        }
    }

    private boolean isIgnoredPath(Path path) {
        String pathStr = path.toString().toLowerCase();
        return pathStr.contains("node_modules") ||
               pathStr.contains("target") ||
               pathStr.contains("build") ||
               pathStr.contains(".git") ||
               pathStr.contains(".idea") ||
               pathStr.contains("dist") ||
               pathStr.contains("out") ||
               pathStr.contains("bin") ||
               pathStr.contains(".gradle");
    }

    private String detectProjectType(String projectRoot) {
        Path root = Paths.get(projectRoot);

        // Check for Maven/Java project
        if (Files.exists(root.resolve("pom.xml"))) {
            return "maven";
        }

        // Check for Gradle project
        if (Files.exists(root.resolve("build.gradle")) || Files.exists(root.resolve("build.gradle.kts"))) {
            return "gradle";
        }

        // Check for Node.js project
        if (Files.exists(root.resolve("package.json"))) {
            return "node";
        }

        // Check for Python project
        if (Files.exists(root.resolve("requirements.txt")) ||
            Files.exists(root.resolve("setup.py")) ||
            Files.exists(root.resolve("pyproject.toml"))) {
            return "python";
        }

        // Check for .NET project
        try (Stream<Path> paths = Files.list(root)) {
            if (paths.anyMatch(p -> p.toString().endsWith(".csproj") || p.toString().endsWith(".sln"))) {
                return "dotnet";
            }
        } catch (Exception e) {
            // Ignore
        }

        return "unknown";
    }

    private void applyRules(Path file, JsonNode registry, DetectionResult result) {
        String fileName = file.getFileName().toString().toLowerCase();

        registry.fields().forEachRemaining(category -> {
            String categoryName = category.getKey();
            JsonNode items = category.getValue();

            items.fields().forEachRemaining(entry -> {
                String techName = entry.getKey();
                JsonNode config = entry.getValue();

                // Skip technologies not relevant to detected project type
                if (!isTechRelevantToProject(techName, config)) {
                    return;
                }

                // Check extensions
                if (config.has("extensions")) {
                    for (JsonNode ext : config.get("extensions")) {
                        if (fileName.endsWith(ext.asText())) {
                            result.add(categoryName, techName, null);
                        }
                    }
                }

                // Check buildFiles
                if (config.has("buildFiles")) {
                    for (JsonNode buildFile : config.get("buildFiles")) {
                        if (fileName.equals(buildFile.asText().toLowerCase())) {
                            String extractedVersion = extractVersion(file, techName, buildFile.asText());
                            result.add(categoryName, techName, extractedVersion);
                        }
                    }
                }

                // Check fileIndicators
                if (config.has("fileIndicators")) {
                    for (JsonNode fileIndicator : config.get("fileIndicators")) {
                        if (fileName.equals(fileIndicator.asText().toLowerCase())) {
                            String extractedVersion = extractVersion(file, techName, fileIndicator.asText());
                            result.add(categoryName, techName, extractedVersion);
                        }
                    }
                }

                // Check indicators in file content
                if (config.has("indicators")) {
                    try {
                        String content = getFileContent(file);
                        if (content != null) {
                            String lowerContent = content.toLowerCase();
                            for (JsonNode indicator : config.get("indicators")) {
                                String indicatorText = indicator.asText();
                                // Use indexOf instead of regex for better performance
                                if (lowerContent.contains(indicatorText.toLowerCase())) {
                                    // For pom.xml files, use specialized extraction
                                    String extractedVersion;
                                    if (fileName.equals("pom.xml")) {
                                        extractedVersion = extractFromPomXml(content, techName);
                                    } else if (fileName.equals("requirements.txt")) {
                                        extractedVersion = extractFromRequirements(content, techName);
                                    } else if (fileName.equals("package.json")) {
                                        extractedVersion = extractFromPackageJson(content, techName);
                                    } else {
                                        extractedVersion = extractVersionFromContent(content, indicatorText);
                                    }
                                    result.add(categoryName, techName, extractedVersion);
                                    break; // Found indicator, no need to check others
                                }
                            }
                        }
                    } catch (Exception ignore) {}
                }
            });
        });
    }

    private String getFileContent(Path file) {
        try {
            String filePath = file.toString();
            // Only cache important files that are read multiple times
            if (filePath.endsWith("pom.xml") || filePath.endsWith("package.json") ||
                filePath.endsWith("requirements.txt") || filePath.endsWith("build.gradle")) {
                return fileContentCache.get().computeIfAbsent(filePath, k -> {
                    try {
                        // Skip files larger than 1MB to avoid memory issues
                        if (Files.size(file) > 1_000_000) {
                            return null;
                        }
                        return Files.readString(file);
                    } catch (Exception e) {
                        return null;
                    }
                });
            }
            // For other files, read directly without caching
            if (Files.size(file) > 1_000_000) {
                return null;
            }
            return Files.readString(file);
        } catch (Exception e) {
            return null;
        }
    }

    private String extractVersion(Path file, String techName, String buildFileName) {
        try {
            String content = getFileContent(file);
            if (content == null) {
                return null;
            }

            // Extract from package.json
            if (buildFileName.equalsIgnoreCase("package.json")) {
                return extractFromPackageJson(content, techName);
            }

            // Extract from pom.xml
            if (buildFileName.equalsIgnoreCase("pom.xml")) {
                return extractFromPomXml(content, techName);
            }

            // Extract from build.gradle or build.gradle.kts
            if (buildFileName.equalsIgnoreCase("build.gradle") || buildFileName.equalsIgnoreCase("build.gradle.kts")) {
                return extractFromGradle(content, techName);
            }

            // Extract from requirements.txt
            if (buildFileName.equalsIgnoreCase("requirements.txt")) {
                return extractFromRequirements(content, techName);
            }

            // Extract from Dockerfile
            if (buildFileName.equalsIgnoreCase("Dockerfile")) {
                return extractFromDockerfile(content, techName);
            }

            // Extract from Kubernetes YAML files
            if (buildFileName.endsWith(".yaml") || buildFileName.endsWith(".yml")) {
                return extractFromYaml(content, techName);
            }

            // Extract Node version from .nvmrc or .node-version
            if (buildFileName.equals(".nvmrc") || buildFileName.equals(".node-version")) {
                return content.trim();
            }

        } catch (Exception e) {
            // Ignore
        }
        return null;
    }

    private String extractFromPackageJson(String content, String techName) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(content);

            // Check dependencies
            if (root.has("dependencies")) {
                JsonNode deps = root.get("dependencies");
                if (deps.has(techName)) {
                    return cleanVersion(deps.get(techName).asText());
                }
            }

            // Check devDependencies
            if (root.has("devDependencies")) {
                JsonNode devDeps = root.get("devDependencies");
                if (devDeps.has(techName)) {
                    return cleanVersion(devDeps.get(techName).asText());
                }
            }

            // Check engines for node
            if (techName.equals("node_runtime") && root.has("engines") && root.get("engines").has("node")) {
                return cleanVersion(root.get("engines").get("node").asText());
            }

        } catch (Exception e) {
            // Ignore
        }
        return null;
    }

    private String extractFromPomXml(String content, String techName) {
        try {
            String lowerTechName = techName.toLowerCase();

            // Extract Java/JDK version - handles both "java" and "jdk" tech names
            if (lowerTechName.equals("java") || lowerTechName.equals("jdk") ||
                lowerTechName.contains("java") || lowerTechName.contains("jdk")) {
                Pattern javaVersionPattern = Pattern.compile("<(java\\.version|maven\\.compiler\\.source|maven\\.compiler\\.target)>(.*?)</");
                Matcher matcher = javaVersionPattern.matcher(content);
                if (matcher.find()) {
                    return matcher.group(2).trim();
                }
            }

            // Extract Spring Boot/Framework version from parent - handles "spring_framework" tech name
            if (lowerTechName.equals("spring_framework") || lowerTechName.contains("spring")) {
                Pattern springBootParentPattern = Pattern.compile(
                    "<parent>.*?<groupId>org\\.springframework\\.boot</groupId>.*?<artifactId>spring-boot-starter-parent</artifactId>.*?<version>(.*?)</version>.*?</parent>",
                    Pattern.DOTALL
                );
                Matcher matcher = springBootParentPattern.matcher(content);
                if (matcher.find()) {
                    return matcher.group(1).trim();
                }

                // Also check for Spring Framework direct dependency
                Pattern springFrameworkPattern = Pattern.compile(
                    "<dependency>.*?<groupId>org\\.springframework</groupId>.*?<version>(.*?)</version>.*?</dependency>",
                    Pattern.DOTALL
                );
                matcher = springFrameworkPattern.matcher(content);
                if (matcher.find()) {
                    return matcher.group(1).trim();
                }
            }

            // Extract PostgreSQL version
            if (lowerTechName.contains("postgres")) {
                // First check if dependency exists with explicit version
                Pattern postgresPattern = Pattern.compile(
                    "<dependency>.*?<groupId>org\\.postgresql</groupId>.*?<artifactId>postgresql</artifactId>.*?<version>(.*?)</version>.*?</dependency>",
                    Pattern.DOTALL
                );
                Matcher matcher = postgresPattern.matcher(content);
                if (matcher.find()) {
                    return matcher.group(1).trim();
                }

                // Check if dependency exists without explicit version (managed by parent)
                Pattern postgresDependencyPattern = Pattern.compile(
                    "<dependency>.*?<groupId>org\\.postgresql</groupId>.*?<artifactId>postgresql</artifactId>.*?</dependency>",
                    Pattern.DOTALL
                );
                if (postgresDependencyPattern.matcher(content).find()) {
                    // Dependency exists but version is managed by parent - return null to show NA
                    return null;
                }
            }

            // Extract MySQL version
            if (lowerTechName.contains("mysql")) {
                Pattern mysqlPattern = Pattern.compile(
                    "<dependency>.*?<groupId>mysql</groupId>.*?<artifactId>mysql-connector-java</artifactId>.*?<version>(.*?)</version>.*?</dependency>",
                    Pattern.DOTALL
                );
                Matcher matcher = mysqlPattern.matcher(content);
                if (matcher.find()) {
                    return matcher.group(1).trim();
                }
            }

            // Extract MongoDB version
            if (lowerTechName.contains("mongo")) {
                Pattern mongoPattern = Pattern.compile(
                    "<dependency>.*?<groupId>org\\.mongodb</groupId>.*?<version>(.*?)</version>.*?</dependency>",
                    Pattern.DOTALL
                );
                Matcher matcher = mongoPattern.matcher(content);
                if (matcher.find()) {
                    return matcher.group(1).trim();
                }
            }

            // Extract Hibernate version
            if (lowerTechName.contains("hibernate")) {
                Pattern hibernatePattern = Pattern.compile(
                    "<dependency>.*?<groupId>org\\.hibernate</groupId>.*?<version>(.*?)</version>.*?</dependency>",
                    Pattern.DOTALL
                );
                Matcher matcher = hibernatePattern.matcher(content);
                if (matcher.find()) {
                    return matcher.group(1).trim();
                }
            }

            // Extract Lombok version
            if (lowerTechName.contains("lombok")) {
                Pattern lombokPattern = Pattern.compile(
                    "<dependency>.*?<groupId>org\\.projectlombok</groupId>.*?<artifactId>lombok</artifactId>.*?<version>(.*?)</version>.*?</dependency>",
                    Pattern.DOTALL
                );
                Matcher matcher = lombokPattern.matcher(content);
                if (matcher.find()) {
                    return matcher.group(1).trim();
                }
            }

            // Extract Kubernetes/Docker dependency versions
            if (lowerTechName.contains("kubernetes") || lowerTechName.contains("k8s")) {
                Pattern k8sPattern = Pattern.compile(
                    "<dependency>.*?<artifactId>.*?kubernetes.*?</artifactId>.*?<version>(.*?)</version>.*?</dependency>",
                    Pattern.DOTALL | Pattern.CASE_INSENSITIVE
                );
                Matcher matcher = k8sPattern.matcher(content);
                if (matcher.find()) {
                    return matcher.group(1).trim();
                }
            }

            // Generic dependency extraction - try to match groupId or artifactId with techName
            String artifactIdPattern = techName.toLowerCase()
                .replace("_", "-")
                .replace(" ", "-")
                .replace(".", "\\.");

            // First try exact artifactId match
            Pattern exactArtifactPattern = Pattern.compile(
                "<dependency>.*?<artifactId>" + Pattern.quote(artifactIdPattern) + "</artifactId>.*?<version>(.*?)</version>.*?</dependency>",
                Pattern.DOTALL | Pattern.CASE_INSENSITIVE
            );
            Matcher matcher = exactArtifactPattern.matcher(content);
            if (matcher.find()) {
                return matcher.group(1).trim();
            }

            // Then try partial match
            Pattern genericPattern = Pattern.compile(
                "<dependency>.*?<artifactId>.*?" + Pattern.quote(artifactIdPattern) + ".*?</artifactId>.*?<version>(.*?)</version>.*?</dependency>",
                Pattern.DOTALL | Pattern.CASE_INSENSITIVE
            );
            matcher = genericPattern.matcher(content);
            if (matcher.find()) {
                return matcher.group(1).trim();
            }

            // Check properties section for version variables
            Pattern propertyPattern = Pattern.compile(
                "<" + Pattern.quote(artifactIdPattern) + "\\.version>(.*?)</" + Pattern.quote(artifactIdPattern) + "\\.version>",
                Pattern.CASE_INSENSITIVE
            );
            matcher = propertyPattern.matcher(content);
            if (matcher.find()) {
                return matcher.group(1).trim();
            }

        } catch (Exception e) {
            // Ignore
        }
        return null;
    }

    private String extractVersionFromContent(String content, String indicator) {
        // Try to find version pattern near the indicator
        Pattern versionPattern = Pattern.compile(Pattern.quote(indicator) + "[\"']?\\s*[:]?\\s*[\"']?([0-9]+\\.[0-9]+\\.?[0-9]*[^\"'\\s,]*)");
        Matcher matcher = versionPattern.matcher(content);
        if (matcher.find()) {
            return cleanVersion(matcher.group(1));
        }
        return null;
    }

    private String cleanVersion(String version) {
        if (version == null) return null;
        // Remove ^, ~, >=, etc. from version strings
        return version.replaceAll("[^0-9.]", "").replaceAll("\\.$", "");
    }

    private boolean isTechRelevantToProject(String techName, JsonNode config) {
        String projectType = detectedProjectType.get();
        if (projectType == null || projectType.equals("unknown")) {
            return true; // Check everything if project type is unknown
        }

        // Define Python-specific technologies
        Set<String> pythonTechs = new HashSet<>(Arrays.asList(
            "python", "django", "flask", "fastapi", "pytest", "pip",
            "virtualenv", "conda", "poetry", "numpy", "pandas"
        ));

        // Define Java-specific technologies
        Set<String> javaTechs = new HashSet<>(Arrays.asList(
            "java", "maven", "gradle", "spring", "springboot", "hibernate",
            "junit", "testng", "tomcat", "jetty"
        ));

        // Define Node.js-specific technologies
        Set<String> nodeTechs = new HashSet<>(Arrays.asList(
            "node_runtime", "npm", "yarn", "react", "vue", "angular",
            "express", "next", "webpack", "typescript", "javascript"
        ));

        // Define .NET-specific technologies
        Set<String> dotnetTechs = new HashSet<>(Arrays.asList(
            "dotnet", "csharp", "aspnet", "nuget", "msbuild"
        ));

        // Filter based on detected project type
        switch (projectType) {
            case "maven":
            case "gradle":
                // For Java projects, skip Python and Node.js specific techs
                if (pythonTechs.contains(techName.toLowerCase()) ||
                    nodeTechs.contains(techName.toLowerCase())) {
                    return false;
                }
                break;

            case "python":
                // For Python projects, skip Java and Node.js specific techs
                if (javaTechs.contains(techName.toLowerCase()) ||
                    nodeTechs.contains(techName.toLowerCase())) {
                    return false;
                }
                break;

            case "node":
                // For Node.js projects, skip Java and Python specific techs
                if (javaTechs.contains(techName.toLowerCase()) ||
                    pythonTechs.contains(techName.toLowerCase())) {
                    return false;
                }
                break;

            case "dotnet":
                // For .NET projects, skip other language-specific techs
                if (javaTechs.contains(techName.toLowerCase()) ||
                    pythonTechs.contains(techName.toLowerCase()) ||
                    nodeTechs.contains(techName.toLowerCase())) {
                    return false;
                }
                break;

            default:
                // Unknown project type, allow all technologies
                break;
        }

        // Also check file extensions to filter out
        if (config.has("extensions")) {
            for (JsonNode ext : config.get("extensions")) {
                String extension = ext.asText().toLowerCase();

                // Skip Python files for Maven/Gradle projects
                if ((projectType.equals("maven") || projectType.equals("gradle")) &&
                    extension.equals(".py")) {
                    return false;
                }

                // Skip Java files for Python projects
                if (projectType.equals("python") && extension.equals(".java")) {
                    return false;
                }

                // Skip JS/TS files for Java/Python projects (unless it's for frontend)
                if ((projectType.equals("maven") || projectType.equals("gradle") ||
                     projectType.equals("python")) &&
                    (extension.equals(".js") || extension.equals(".ts"))) {
                    // Allow common frontend files in any project
                    continue;
                }
            }
        }

        return true;
    }

    private String extractFromGradle(String content, String techName) {
        try {
            String lowerTechName = techName.toLowerCase();

            // Extract Java version
            if (lowerTechName.contains("java") || lowerTechName.contains("jdk")) {
                Pattern javaPattern = Pattern.compile("(sourceCompatibility|targetCompatibility)\\s*=\\s*['\"]?(\\d+\\.?\\d*)['\"]?");
                Matcher matcher = javaPattern.matcher(content);
                if (matcher.find()) {
                    return matcher.group(2).trim();
                }
            }

            // Extract dependency versions
            String artifactPattern = techName.toLowerCase().replace("_", "-");
            Pattern versionPattern = Pattern.compile(
                "\\b" + Pattern.quote(artifactPattern) + "[\"']?\\s*[:]\\s*[\"']([0-9]+\\.[0-9]+\\.?[0-9]*)[\"']",
                Pattern.CASE_INSENSITIVE
            );
            Matcher matcher = versionPattern.matcher(content);
            if (matcher.find()) {
                return matcher.group(1).trim();
            }
        } catch (Exception e) {
            // Ignore
        }
        return null;
    }

    private String extractFromRequirements(String content, String techName) {
        try {
            String[] lines = content.split("\\n");
            String techPattern = techName.toLowerCase()
                .replace("_", "[-_]")
                .replace("-", "[-_]");

            for (String line : lines) {
                line = line.trim();
                // Skip comments and empty lines
                if (line.startsWith("#") || line.isEmpty()) {
                    continue;
                }

                // Match package names (case-insensitive, with _ or -)
                Pattern packagePattern = Pattern.compile(
                    "^" + techPattern + "\\s*([=<>~!]+)\\s*([0-9]+\\.[0-9]+\\.?[0-9]*)",
                    Pattern.CASE_INSENSITIVE
                );
                Matcher matcher = packagePattern.matcher(line);
                if (matcher.find()) {
                    return matcher.group(2).trim();
                }

                // Also handle format like: django==4.2.0
                if (line.toLowerCase().matches("^" + techPattern + ".*")) {
                    Pattern versionPattern = Pattern.compile("([=<>~!]+)\\s*([0-9]+\\.[0-9]+\\.?[0-9]*)");
                    matcher = versionPattern.matcher(line);
                    if (matcher.find()) {
                        return matcher.group(2).trim();
                    }
                }
            }
        } catch (Exception e) {
            // Ignore
        }
        return null;
    }

    private String extractFromDockerfile(String content, String techName) {
        try {
            String lowerTechName = techName.toLowerCase();

            // Extract base image versions
            if (lowerTechName.contains("java") || lowerTechName.contains("jdk")) {
                Pattern javaPattern = Pattern.compile("FROM\\s+[\\w/.:-]*(?:java|jdk|openjdk)[:]([0-9]+\\.?[0-9]*)", Pattern.CASE_INSENSITIVE);
                Matcher matcher = javaPattern.matcher(content);
                if (matcher.find()) {
                    return matcher.group(1).trim();
                }
            }

            if (lowerTechName.contains("python")) {
                Pattern pythonPattern = Pattern.compile("FROM\\s+python[:]([0-9]+\\.[0-9]+\\.?[0-9]*)", Pattern.CASE_INSENSITIVE);
                Matcher matcher = pythonPattern.matcher(content);
                if (matcher.find()) {
                    return matcher.group(1).trim();
                }
            }

            if (lowerTechName.contains("node")) {
                Pattern nodePattern = Pattern.compile("FROM\\s+node[:]([0-9]+\\.[0-9]+\\.?[0-9]*)", Pattern.CASE_INSENSITIVE);
                Matcher matcher = nodePattern.matcher(content);
                if (matcher.find()) {
                    return matcher.group(1).trim();
                }
            }
        } catch (Exception e) {
            // Ignore
        }
        return null;
    }

    private String extractFromYaml(String content, String techName) {
        try {
            String lowerTechName = techName.toLowerCase();

            // Extract Kubernetes version from apiVersion
            if (lowerTechName.contains("kubernetes") || lowerTechName.equals("k8s")) {
                Pattern k8sPattern = Pattern.compile("apiVersion:\\s*[\\w./]+/v([0-9]+[a-z]*[0-9]*)");
                Matcher matcher = k8sPattern.matcher(content);
                if (matcher.find()) {
                    return matcher.group(1).trim();
                }
            }

            // Extract image versions
            Pattern imagePattern = Pattern.compile("image:\\s*[\\w/.:-]*" + Pattern.quote(lowerTechName) + "[:]([0-9]+\\.[0-9]+\\.?[0-9]*)", Pattern.CASE_INSENSITIVE);
            Matcher matcher = imagePattern.matcher(content);
            if (matcher.find()) {
                return matcher.group(1).trim();
            }
        } catch (Exception e) {
            // Ignore
        }
        return null;
    }
}
