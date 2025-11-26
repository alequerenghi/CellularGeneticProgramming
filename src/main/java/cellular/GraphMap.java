package cellular;

import java.util.List;
import java.util.Map;

@FunctionalInterface
public interface GraphMap {

	Map<Integer, List<Integer>> getConnections(int populationSize, int matrixSize);

}
