package depsolver;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.TypeReference;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;

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
        if (isValid(packageList) && !isSeen(packageList)) {
            seenRepos.add(packageList);
            if (isFinal(packageList)) {
                if (getCommandsCost(commands) < solvedCommandsCost) {
                    solvedRepo = packageList;
                    solvedCommands = commands;
                    solvedCommandsCost = getCommandsCost(commands);
                }
            } else {
                for (Package p : repo) {
                    boolean install = !packageList.contains(p);
                    List<Package> newRepo = packageList;
                    List<String> newCommands = commands;

                    if(install) {
                        newRepo = installPackage(packageList, p);
                        newCommands = getNewCommands(true, p, commands);
                    } else {
                        if(!haveAlreadyInstalled(commands, p)) {
                            newRepo = uninstallPackage(packageList, p);
                            newCommands = getNewCommands(false, p, commands);
                        }
                    }

                    search(newRepo, newCommands);
                }
            }
        }
    }

    private static boolean isValid(List<Package> repo) {
        if(repo.size() == 0) return true;

        List<Package> repoClone = new ArrayList<>(repo);

        boolean validConfs = checkConflicts(repoClone);

        Package mostRecent = repoClone.remove(repo.size() - 1);
        List<List<String>> deps = mostRecent.getDepends();
        boolean validDeps = checkDependencies(repoClone, deps);


        return validDeps && validConfs;
    }

    private static boolean checkDependencies(List<Package> repo, List<List<String>> deps) {
        for(List<String> clause : deps) {
            boolean clauseMet = false;
            for(String dep : clause) {
                boolean met = false;
                for(Package pack : repo) {
                    if(checkRequirement(dep, pack)) met = true;
                }
                clauseMet |= met;
            }
            if(!clauseMet) return false;
        }

        return true;
    }

    private static boolean checkConflicts(List<Package> repo) {
        for(Package pack : repo) {
            List<Package> otherPackages = new ArrayList<>(repo);
            otherPackages.remove(pack);
            for(String conf : pack.getConflicts()) {
                for(Package otherPack : otherPackages) {
                    if(checkRequirement(conf, otherPack)) return false;
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
                    cost += pack.getSize();
                } else {
                    Package pack = repo.stream().filter(p -> p.getName().equals(name) && p.getVersion().equals(version)).findFirst().orElse(null);
                    cost += pack.getSize();
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

    private static boolean haveAlreadyInstalled(List<String> commands, Package pack) {
        for(String command : commands) {
            String[] commandSplit = split(command);
            String name = commandSplit[0];
            String version = commandSplit[1];

            if(version.equals("") && pack.getName().equals(name)) return true;
            if(!version.equals("") && pack.getName().equals(name) && pack.getVersion().equals(version)) return true;
        }
        return false;
    }

    private static String[] split(String string) {
        String[] commandSplit = string.substring(1).split("=");
        String name = commandSplit[0];
        String version = (commandSplit.length > 1) ? commandSplit[1] : "";

        return new String[] {name, version};
    }

    private static List<String> getNewCommands(boolean install, Package p, List<String> commands) {
        String command = generateCommand(install, p);
        List<String> commandsClone = new ArrayList<>(commands);
        commandsClone.add(command);

        return commandsClone;
    }

    private static String generateCommand(boolean install, Package p) {
        char firstCharacter = install ? '+' : '-';
        return firstCharacter + p.getName() + "=" + p.getVersion();
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
