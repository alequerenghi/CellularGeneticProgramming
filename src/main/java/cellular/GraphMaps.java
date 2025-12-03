package cellular;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.random.RandomGenerator;

import io.jenetics.util.RandomRegistry;

public class GraphMaps {

	private static final RandomGenerator RNG = RandomRegistry.random();

	private GraphMaps() {
		// nothing
	}

	public static GraphMap grid(int popSize) {
		int gridSize = (int) Math.sqrt(popSize);
		return () -> {
			HashMap<Integer, List<Integer>> map = new HashMap<>();
			for (int i = 0; i < popSize; i++) {
				ArrayList<Integer> neighbors = new ArrayList<>();
				neighbors.add((i + 1) % popSize);
				neighbors.add(i - 1 < 0 ? popSize - 1 : i - 1);
				neighbors.add((i + gridSize) % popSize);
				neighbors.add(i - gridSize < 0 ? popSize - gridSize + i : i - gridSize);
				map.put(i, neighbors);
			}
			return map;
		};
	}

	public static GraphMap multipleInAndOutNodes(int numNodes, double highInFraction, double highOutFraction,
			int avgDegree) {

		return () -> {
			Map<Integer, List<Integer>> graph = new HashMap<>();

			// Initialize empty adjacency lists
			for (int i = 0; i < numNodes; i++) {
				graph.put(i, new ArrayList<>());
			}

			// Pick hub nodes
			int numInHubs = (int) (numNodes * highInFraction);
			int numOutHubs = (int) (numNodes * highOutFraction);

			Set<Integer> inHubs = pickRandomSet(numNodes, numInHubs);
			Set<Integer> outHubs = pickRandomSet(numNodes, numOutHubs);

			// Build edges
			for (int from = 0; from < numNodes; from++) {

				int degree;

				if (outHubs.contains(from)) {
					// Out-hubs have more outgoing edges
					degree = avgDegree * 4;
				} else {
					// Normal nodes
					degree = avgDegree;
				}

				for (int k = 0; k < degree; k++) {
					int to = RNG.nextInt(numNodes);
					if (to == from)
						continue; // avoid self-loop

					graph.get(from).add(to);
				}
			}

			// Add extra incoming edges for in-hubs
			for (int hub : inHubs) {
				int extraIn = avgDegree * 4;

				for (int k = 0; k < extraIn; k++) {
					int from = RNG.nextInt(numNodes);
					if (from == hub)
						continue;

					graph.get(from).add(hub);

				}
			}
			return graph;
		};
	}

	public static GraphMap barabasiAlbert(int n, int m0, int m) {
		return () -> {
			Map<Integer, List<Integer>> g = new HashMap<>();

			// Initialize adjacency lists
			for (int i = 0; i < n; i++)
				g.put(i, new ArrayList<>());

			// Start with m0 fully connected nodes
			for (int i = 0; i < m0; i++) {
				for (int j = 0; j < m0; j++) {
					if (i != j)
						g.get(i).add(j);
				}
			}

			// Degree list for preferential attachment
			List<Integer> degrees = new ArrayList<>();
			for (int i = 0; i < m0; i++)
				for (int j = 0; j < g.get(i).size(); j++)
					degrees.add(i);

			// Add remaining nodes
			for (int newNode = m0; newNode < n; newNode++) {

				Set<Integer> targets = new HashSet<>();
				while (targets.size() < m) {
					int chosen = degrees.get(RNG.nextInt(degrees.size()));
					if (chosen != newNode)
						targets.add(chosen);
				}

				for (int t : targets) {
					g.get(newNode).add(t);
					degrees.add(newNode);
					degrees.add(t);
				}
			}

			return g;
		};
	}

	public static GraphMap wattsStrogatz(int n, int k, double beta) {
		return () -> {
			Map<Integer, List<Integer>> g = new HashMap<>();

			for (int i = 0; i < n; i++)
				g.put(i, new ArrayList<>());

			// Ring lattice
			for (int i = 0; i < n; i++) {
				for (int j = 1; j <= k / 2; j++) {
					int nei = (i + j) % n;
					g.get(i).add(nei);
				}
			}

			// Rewire edges
			for (int i = 0; i < n; i++) {
				List<Integer> outs = g.get(i);

				for (int idx = 0; idx < outs.size(); idx++) {
					if (RNG.nextDouble() < beta) {
						int newTarget;
						do {
							newTarget = RNG.nextInt(n);
						} while (newTarget == i || outs.contains(newTarget));

						outs.set(idx, newTarget);
					}
				}
			}
			return g;
		};
	}

	public static GraphMap erdosRenyi(int n, double p) {
		return () -> {
			Map<Integer, List<Integer>> g = new HashMap<>();

			for (int i = 0; i < n; i++)
				g.put(i, new ArrayList<>());

			for (int i = 0; i < n; i++) {
				for (int j = 0; j < n; j++) {
					if (i != j && RNG.nextDouble() < p) {
						g.get(i).add(j);
					}
				}
			}

			return g;
		};
	}

	public static GraphMap layeredDAG(int layers, int nodesPerLayer, double forwardProb) {
		return () -> {
			int total = layers * nodesPerLayer;
			Map<Integer, List<Integer>> g = new HashMap<>();

			for (int i = 0; i < total; i++)
				g.put(i, new ArrayList<>());

			for (int l = 0; l < layers - 1; l++) {
				for (int i = l * nodesPerLayer; i < (l + 1) * nodesPerLayer; i++) {
					for (int j = (l + 1) * nodesPerLayer; j < (l + 2) * nodesPerLayer; j++) {
						if (RNG.nextDouble() < forwardProb) {
							g.get(i).add(j);
						}
					}
				}
			}

			return g;
		};
	}

	/** Helper to pick a random subset of nodes */
	private static Set<Integer> pickRandomSet(int max, int count) {
		Set<Integer> set = new HashSet<>();
		while (set.size() < count) {
			set.add(RNG.nextInt(max));
		}
		return set;
	}

}
