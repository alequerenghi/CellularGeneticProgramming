package cellular;

import java.util.List;
import java.util.Map;

public class GraphMap {

	private final Map<Integer, List<Integer>> map;
	private final String mapName;

	protected GraphMap(Map<Integer, List<Integer>> map, String mapName) {
		this.map = map;
		this.mapName = mapName;
	}

	public List<Integer> getConnections(int node) {
		return map.get(node);
	}

	@Override
	public String toString() {
		return mapName;
	}

	public int size() {
		return map.size();
	}

}
