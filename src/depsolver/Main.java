package depsolver;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.TypeReference;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.*;

class Package {
    private String name;
    private String version;
    private Integer size;
    private List<List<String>> depends = new ArrayList<>();
    private List<String> conflicts = new ArrayList<>();

    String getName() { return name; }
    String getVersion() { return version; }
    Integer getSize() { return size; }
    List<List<String>> getDepends() { return depends; }
    List<String> getConflicts() { return conflicts; }

    public void setName(String name) { this.name = name; }
    public void setVersion(String version) { this.version = version; }
    public void setSize(Integer size) { this.size = size; }
    public void setDepends(List<List<String>> depends) { this.depends = depends; }
    public void setConflicts(List<String> conflicts) { this.conflicts = conflicts; }
}

public class Main {
    private static List<Package> repo;
    private static List<String> constraints;
    private static HashSet<List<Package>> seenRepos = new HashSet<>();
    private static List<Package> solvedRepo = null;
    private static List<String> solvedCommands = null;
    private static int solvedCommandsCost = Integer.MAX_VALUE;

    private static long startTime = new Date().getTime();
    private static boolean terminate = false;

    public static void main(String[] args) throws IOException {
        TypeReference<List<Package>> repoType = new TypeReference<List<Package>>() {};
        TypeReference<List<String>> strListType = new TypeReference<List<String>>() {};

        repo = JSON.parseObject(readFile(args[0]), repoType);
        List<String> initial = JSON.parseObject(readFile(args[1]), strListType);
        constraints = JSON.parseObject(readFile(args[2]), strListType);

        List<Package> initialRepo = getInitialRepo(initial);

        search(initialRepo, new ArrayList<>());

        if (solvedRepo == null) {
            System.out.println("No solution found.");
            return;
        }

        String commandsJSON = JSON.toJSONString(solvedCommands, true);
        System.out.println(commandsJSON);
    }

    private static String readFile(String filename) throws IOException {
        BufferedReader br = new BufferedReader(new FileReader(filename));
        StringBuilder sb = new StringBuilder();
        br.lines().forEach(sb::append);
        return sb.toString();
    }

    private static List<Package> getInitialRepo(List<String> initial) {
        List<Package> initialRepo = new ArrayList<>();

        for(String s : initial) {
            String[] split = s.split("=");
            Package pack;

            if(split.length > 1) pack = repo.stream().
                    filter(p -> p.getName().equals(split[0]) && p.getVersion().equals(split[1])).
                    findFirst().orElse(null);
            else pack = repo.stream().
                    filter(p -> p.getName().equals(split[0])).
                    findFirst().orElse(null);

            initialRepo.add(pack);
        }

        return initialRepo;
    }

    private static void search(List<Package> packageList, List<String> commands) {
        if (!terminate && !isSeen(packageList) && isValid(packageList)) {
            seenRepos.add(packageList);

            if (isFinal(packageList)) {
                int commandsCost = getCommandsCost(commands);

                if (commandsCost < solvedCommandsCost) {
                    solvedRepo = packageList;
                    solvedCommands = commands;
                    solvedCommandsCost = commandsCost;
                } else if (exceededPracticalRunTime()) terminate = true;
            } else {
                for (Package p : repo) {
                    boolean installed = packageList.contains(p);
                    List<Package> newRepo = packageList;
                    List<String> newCommands = commands;

                    if(!installed) {
                        if(!haveUninstalled(commands, p)) {
                            newRepo = installPackage(packageList, p);
                            newCommands = getNewCommands(true, p, commands);
                        }
                    } else {
                        if((!haveInstalled(commands, p) || commands.size() < constraints.size()) && shouldUninstall(p)) {
                            newRepo = uninstallPackage(packageList, p);
                            newCommands = getNewCommands(false, p, commands);
                        }
                    }

                    search(newRepo, newCommands);
                }
            }

            seenRepos.remove(packageList);
        }
    }

    private static boolean exceededPracticalRunTime() {
        return new Date().getTime() - startTime > 60000;
    }

    private static boolean shouldUninstall(Package pack) {
        String name = pack.getName();
        String version = pack.getVersion();
        String packString = version.equals("") ? name : name + "=" + version;

        if(constraints.contains('+' + packString)) return false;
        else if(constraints.contains('-' + packString)) return true;

        return true;
    }

    private static boolean isValid(List<Package> repo) {
        if(repo.size() == 0) return true;

        Package mostRecent = repo.get(repo.size() - 1);
        List<List<String>> deps = mostRecent.getDepends();
        if(!checkDependencies(repo, deps)) return false;

        return checkConflicts(repo);
    }

    private static boolean checkDependencies(List<Package> repo, List<List<String>> deps) {
        for(List<String> clause : deps) {
            boolean clauseMet = false;
            for(String dep : clause) {
                for(int i = 0; i < repo.size() - 1; i++) {
                    if(checkRequirement(dep, repo.get(i))) { clauseMet = true; break; }
                }
                if(clauseMet) break;
            }
            if(!clauseMet) return false;
        }

        return true;
    }

    private static boolean checkConflicts(List<Package> repo) {
        for(Package pack : repo) {
            for(String conf : pack.getConflicts()) {
                for(Package otherPack : repo) {
                    if(!pack.equals(otherPack)) {
                        if(checkRequirement(conf, otherPack)) return false;
                    }
                }
            }
        }

        return true;
    }

    private static boolean isFinal(List<Package> repo) {
        for(String constraint : constraints) {
            boolean install = constraint.charAt(0) == '+';

            String[] constraintSplit = split(constraint);
            String name = constraintSplit[0];
            String version = constraintSplit[1];

            if(install) {
                boolean installed = false;
                for(Package p : repo) {
                    if(version.equals("") && p.getName().equals(name)) { installed = true; break; }
                    if(!version.equals("") && p.getName().equals(name) && p.getVersion().equals(version)) { installed = true; break; }
                }

                if(!installed) return false;
            } else {
                for(Package p : repo) {
                    if(version.equals("") && p.getName().equals(name)) return false;
                    if(!version.equals("") && p.getName().equals(name) && p.getVersion().equals(version)) return false;
                }
            }
        }

        return true;
    }

    private static int getCommandsCost(List<String> commands) {
        int cost = 0;

        for(String command : commands) {
            if(command.charAt(0) == '+') {
                String[] commandSplit = split(command);
                String name = commandSplit[0];
                String version = commandSplit[1];

                if(version.equals("")) {
                    Package pack = repo.stream().filter(p -> p.getName().equals(name)).findFirst().orElse(null);
                    if(pack != null) cost += pack.getSize();
                } else {
                    Package pack = repo.stream().filter(p -> p.getName().equals(name) && p.getVersion().equals(version)).findFirst().orElse(null);
                    if(pack != null) cost += pack.getSize();
                }

            } else {
                cost += 1000000;
            }
        }

        return cost;
    }

    private static boolean isSeen(List<Package> repo) { return seenRepos.contains(repo); }

    private static List<Package> installPackage(List<Package> repo, Package pack) {
        List<Package> repoClone = new ArrayList<>(repo);
        repoClone.add(pack);
        return repoClone;
    }

    private static List<Package> uninstallPackage(List<Package> repo, Package pack) {
        List<Package> repoClone = new ArrayList<>(repo);
        repoClone.remove(pack);
        return repoClone;
    }

    private static boolean haveInstalled(List<String> commands, Package pack) {
        return haveInstalledOrUninstalled('+', commands, pack);
    }

    private static boolean haveUninstalled(List<String> commands, Package pack) {
        return haveInstalledOrUninstalled('-', commands, pack);
    }

    private static boolean haveInstalledOrUninstalled(char c, List<String> commands, Package pack) {
        String name = pack.getName();
        String version = pack.getVersion();
        String command = c + (version.equals("") ? name : name + "=" + version);

        return commands.contains(command);
    }

    private static String[] split(String string) {
        String[] commandSplit = string.substring(1).split("=");
        String name = commandSplit[0];
        String version = (commandSplit.length > 1) ? commandSplit[1] : "";

        return new String[] {name, version};
    }

    private static List<String> getNewCommands(boolean install, Package pack, List<String> commands) {
        String command = generateCommand(install, pack);
        List<String> commandsClone = new ArrayList<>(commands);
        commandsClone.add(command);

        return commandsClone;
    }

    private static String generateCommand(boolean install, Package pack) {
        char firstCharacter = install ? '+' : '-';
        return firstCharacter + pack.getName() + "=" + pack.getVersion();
    }

    // returns -1 if v1 is smaller than v2, 1 if greater, 0 if equal
    private static int compareVersion(String v1, String v2) {
        List<String> p1VersionItemsString = Arrays.asList(v1.split("\\."));
        List<String> p2VersionItemsString = Arrays.asList(v2.split("\\."));

        int[] p1VersionItemsInt = p1VersionItemsString.stream().mapToInt(Integer::parseInt).toArray();
        int[] p2VersionItemsInt = p2VersionItemsString.stream().mapToInt(Integer::parseInt).toArray();

        int iterationCount = Math.min(p1VersionItemsInt.length, p2VersionItemsInt.length);

        for(int i = 0; i < iterationCount; i++) {
            if(p1VersionItemsInt[i] < p2VersionItemsInt[i]) return -1;
            if(p1VersionItemsInt[i] > p2VersionItemsInt[i]) return 1;
        }

        if(p1VersionItemsInt.length == p2VersionItemsInt.length) return 0;
        else return (p1VersionItemsInt.length < p2VersionItemsInt.length) ? -1 : 1;
    }

    private static boolean checkRequirement(String req, Package pack) {
        if(req.contains(">=")) {
            String[] reqSplit = req.split(">=");
            int comparison = compareVersion(pack.getVersion(), reqSplit[1]);
            return reqSplit[0].equals(pack.getName()) && comparison >= 0;
        } else if(req.contains("<=")) {
            String[] reqSplit = req.split("<=");
            int comparison = compareVersion(pack.getVersion(), reqSplit[1]);
            return reqSplit[0].equals(pack.getName()) && comparison <= 0;
        } else if(req.contains(">")) {
            String[] reqSplit = req.split(">");
            int comparison = compareVersion(pack.getVersion(), reqSplit[1]);
            return reqSplit[0].equals(pack.getName()) && comparison == 1;
        } else if(req.contains("<")) {
            String[] reqSplit = req.split("<");
            int comparison = compareVersion(pack.getVersion(), reqSplit[1]);
            return reqSplit[0].equals(pack.getName()) && comparison == -1;
        } else if(req.contains("=")) {
            String[] reqSplit = req.split("=");
            int comparison = compareVersion(pack.getVersion(), reqSplit[1]);
            return reqSplit[0].equals(pack.getName()) && comparison == 0;
        } else {
            return req.equals(pack.getName());
        }
    }
}