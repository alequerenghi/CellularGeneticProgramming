package cellular;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Utility class for generating different graph topologies used by the Cellular
 * Genetic Programming engine. Every graph is represented as an adjacency list:
 * node -> list of neighbor nodes.
 */
public class GraphMaps {

  /** Deterministic global RNG for reproducible topology generation. */
  private static final Random RNG = new Random(42);

  private GraphMaps() {
    // Prevent instantiation.
  }

  /**
   * Generates a 2D toroidal grid topology (wrapped both horizontally and
   * vertically).
   *
   * @param popSize total number of nodes. Should ideally be a square number.
   * @return GraphMap representing toroidal grid neighbors.
   */
  public static GraphMap grid(int popSize) {
    int gridSize = (int) Math.sqrt(popSize);

    HashMap<Integer, List<Integer>> map = new HashMap<>();
    for (int i = 0; i < popSize; i++) {
      ArrayList<Integer> neighbors = new ArrayList<>();

      // Horizontal wrap-around neighbors
      neighbors.add((i + 1) % popSize); // right
      neighbors.add(i - 1 < 0 ? popSize - 1 : i - 1); // left

      // Vertical wrap-around neighbors
      neighbors.add((i + gridSize) % popSize); // down
      neighbors.add(i - gridSize < 0 ? popSize - gridSize + i : i - gridSize); // up

      map.put(i, neighbors);
    }
    return new GraphMap(map, "grid");
  }

  /**
   * Generates a graph where some nodes have many incoming edges (in-hubs) and
   * some nodes have many outgoing edges (out-hubs).
   *
   * This produces an asymmetric structure with directional preference.
   *
   * @param numNodes        total number of nodes
   * @param highInFraction  fraction of nodes designated as in-hubs
   * @param highOutFraction fraction of nodes designated as out-hubs
   * @param avgDegree       baseline average degree for normal nodes
   * @return GraphMap with asymmetric degrees
   */
  public static GraphMap multipleInAndOutNodes(
      int numNodes,
      double highInFraction,
      double highOutFraction,
      int avgDegree) {

    Map<Integer, List<Integer>> graph = new HashMap<>();

    // Initialize adjacency lists
    for (int i = 0; i < numNodes; i++) {
      graph.put(i, new ArrayList<>());
    }

    // Select hub groups
    int numInHubs = (int) (numNodes * highInFraction);
    int numOutHubs = (int) (numNodes * highOutFraction);

    Set<Integer> inHubs = pickRandomSet(numNodes, numInHubs);
    Set<Integer> outHubs = pickRandomSet(numNodes, numOutHubs);

    // Out-hubs get many outgoing edges; other nodes get avgDegree
    for (int from = 0; from < numNodes; from++) {

      int degree;
      if (outHubs.contains(from)) {
        degree = Math.powExact(avgDegree, 2);
      } else {
        degree = avgDegree;
      }

      for (int k = 0; k < degree; k++) {
        int to = RNG.nextInt(numNodes);
        if (to == from)
          continue; // avoid self-loop

        graph.get(from)
             .add(to);
      }
    }

    // In-hubs receive many extra incoming edges
    for (int hub : inHubs) {
      int extraIn = Math.powExact(avgDegree, 2);

      for (int k = 0; k < extraIn; k++) {
        int from = RNG.nextInt(numNodes);
        if (from == hub)
          continue;

        graph.get(from)
             .add(hub);
      }
    }

    return new GraphMap(graph, "Multiple in- and out-nodes");
  }

  /**
   * Barabási–Albert scale-free model generator.
   *
   * Hubs emerge naturally because nodes with higher degree are preferentially
   * selected.
   *
   * Note: The implementation initializes all nodes up front, then attempts a
   * growing process. This deviates from the classical BA model but preserves
   * preferential attachment semantics.
   *
   * @param graphSize number of nodes
   * @param m         number of edges added for each new node (preferentially)
   * @return GraphMap with approximate BA structure
   */
  public static GraphMap barabasiAlbert(int graphSize, int m) {
    int m0 = RNG.nextInt(m, 2 * m);

    Map<Integer, List<Integer>> g = new HashMap<>();

    // Initialize adjacency lists
    for (int i = 0; i < graphSize; i++) {
      g.put(i, new ArrayList<>());
    }

    // Fully connect initial m0 nodes
    for (int i = 0; i < m0; i++) {
      for (int j = 0; j < m0; j++) {
        if (i != j) {
          g.get(i)
           .add(j);
          g.get(j)
           .add(i);
        }
      }
    }

    // Preferentially attach the rest
    while (g.size() < graphSize) {
      int current = g.size();
      g.put(current, new ArrayList<>());

      List<Integer> connections = g.values()
                                   .stream()
                                   .map(List::size)
                                   .collect(Collectors.toCollection(ArrayList::new));

      int nConnections = connections.stream()
                                    .mapToInt(Integer::intValue)
                                    .sum();

      for (int i = 0; i < m; i++) {
        int chosen = proportionalSelection(connections, nConnections);
        g.get(chosen)
         .add(current);
        g.get(current)
         .add(chosen);

        connections.set(chosen, connections.get(chosen) + 1);
        connections.set(current, connections.get(current) + 1);
        nConnections += 2;
      }
    }
    return new GraphMap(g, "Barabasi Albert");
  }

  /**
   * Selects an index proportional to its weight value.
   *
   * @param weights    list of weights
   * @param weightsSum sum of all weights
   * @return selected index
   */
  private static int proportionalSelection(List<Integer> weights, int weightsSum) {
    double r = RNG.nextDouble() * weightsSum;
    int i;
    for (i = 0; i < weights.size() && r > 0; i++) {
      r -= weights.get(i);
    }
    return i;
  }

  /**
   * Watts–Strogatz small-world graph.
   *
   * Starts as a ring lattice where every node connects to k/2 neighbors on each
   * side. Then edges are rewired randomly with probability beta.
   *
   * Rewiring introduces shortcuts, reducing average path length.
   *
   * @param graphSize number of nodes
   * @param k         number of neighbors (must be even)
   * @param beta      rewiring probability
   * @return Watts–Strogatz graph
   */
  public static GraphMap wattsStrogatz(int graphSize, int k, double beta) {

    Map<Integer, List<Integer>> g = new HashMap<>();

    // Regular ring lattice
    for (int i = 0; i < graphSize; i++) {
      g.put(i, new ArrayList<>());
      for (int j = 1; j <= k / 2; j++) {
        int neighbor = (i + j) % graphSize;
        g.get(i)
         .add(neighbor);
      }
    }

    // Rewiring
    for (int i = 0; i < graphSize; i++) {

      List<Integer> outs = g.get(i);

      for (int idx = 0; idx < outs.size(); idx++) {
        if (RNG.nextDouble() < beta) {
          int newTarget;
          do {
            newTarget = RNG.nextInt(graphSize);
          } while (newTarget == i || outs.contains(newTarget));

          outs.set(idx, newTarget);
        }
      }
    }
    return new GraphMap(g, "Watts Strogatz");
  }

  /**
   * Erdos–Renyi random graph G(n, p).
   *
   * Every possible directed edge i -> j exists independently with probability p.
   *
   * @param n number of nodes
   * @param p probability of each directed edge existing
   * @return Erdos–Renyi graph
   */
  public static GraphMap erdosRenyi(int n, double p) {
    Map<Integer, List<Integer>> g = new HashMap<>();

    for (int i = 0; i < n; i++) {
      g.put(i, new ArrayList<>());
      for (int j = 0; j < n; j++) {
        if (i != j && RNG.nextDouble() < p) {
          g.get(i)
           .add(j);
        }
      }
    }
    return new GraphMap(g, "Erdos Renyi");
  }

  /**
   * Generates a Layered Directed Acyclic Graph (DAG).
   *
   * Nodes are arranged in layers, and edges only go from layer L to L+1, never
   * backwards. This enforces acyclicity.
   *
   * @param layers        number of layers
   * @param nodesPerLayer nodes in each layer
   * @param forwardProb   probability of creating an edge from L to L+1
   * @return Layered DAG
   */
  public static GraphMap layeredDAG(final int layers, final int nodesPerLayer, final double forwardProb) {

    int total = layers * nodesPerLayer;
    Map<Integer, List<Integer>> g = new HashMap<>();

    // Initialize graph
    for (int i = 0; i < total; i++) {
      g.put(i, new ArrayList<>());
    }

    // Add forward-only edges
    for (int l = 0; l < layers - 1; l++) {
      for (int i = l * nodesPerLayer; i < (l + 1) * nodesPerLayer; i++) {
        for (int j = (l + 1) * nodesPerLayer; j < (l + 2) * nodesPerLayer; j++) {
          if (RNG.nextDouble() < forwardProb) {
            g.get(i)
             .add(j);
          }
        }
      }
    }
    return new GraphMap(g, "Layered Directed Acyclic graph");
  }

  /**
   * Helper for selecting a random set of unique integers.
   *
   * @param max   exclusive upper bound for values
   * @param count number of unique values required
   * @return set of random integers
   */
  private static Set<Integer> pickRandomSet(int max, int count) {
    Set<Integer> set = new HashSet<>();
    while (set.size() < count) {
      set.add(RNG.nextInt(max));
    }
    return set;
  }

}
