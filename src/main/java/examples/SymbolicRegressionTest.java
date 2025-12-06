package examples;

import static io.jenetics.util.RandomRegistry.random;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;

import com.sun.tools.javac.Main;

import cellular.CellularEngine;
import cellular.GraphMap;
import cellular.GraphMaps;
import io.jenetics.Phenotype;
import io.jenetics.engine.Codec;
import io.jenetics.engine.EvolutionResult;
import io.jenetics.engine.EvolutionStream;
import io.jenetics.ext.util.Tree;
import io.jenetics.ext.util.TreeNode;
import io.jenetics.prog.ProgramGene;
import io.jenetics.prog.op.EphemeralConst;
import io.jenetics.prog.op.MathExpr;
import io.jenetics.prog.op.MathOp;
import io.jenetics.prog.op.Op;
import io.jenetics.prog.op.Var;
import io.jenetics.prog.regression.Error;
import io.jenetics.prog.regression.LossFunction;
import io.jenetics.prog.regression.Regression;
import io.jenetics.prog.regression.Sample;
import io.jenetics.util.ISeq;

public class SymbolicRegressionTest {

  private static final ISeq<Op<Double>> OPS = ISeq.of(MathOp.ADD, MathOp.SUB, MathOp.MUL, MathOp.DIV, MathOp.SQRT,
      MathOp.SIN, MathOp.COS);

  private static final List<GraphMap> GRIDS = List.of(// GraphMaps.grid(100),
//      GraphMaps.barabasiAlbert(100, 5), 
      GraphMaps.multipleInAndOutNodes(100, .3, .3, 5));

  public static void main(String... args) {

    Set<File> inputFiles = Stream.of(new File(Main.class.getClassLoader()
        .getResource("data")
        .getFile()).listFiles())
        .filter(f -> !f.isDirectory())
        .collect(Collectors.toSet());

    try (ExecutorService pool = Executors.newFixedThreadPool(Runtime.getRuntime()
        .availableProcessors())) {

      for (File file : inputFiles) {
        StringBuilder output = new StringBuilder();

        final Regression<Double> regression = generateProblem(file);

        for (GraphMap grid : GRIDS) {

          output.append("\n\n")
              .append(evolveAsync(regression, grid, pool));
        }
//        final EvolutionResult<ProgramGene<Double>, Double> er = engine.stream()
//            .limit(50)
//            .peek(statistics)
//            .collect(EvolutionResult.toBestEvolutionResult());
//
//        final ProgramGene<Double> gene = er.bestPhenotype()
//            .genotype()
//            .gene();
//
//        TreeNode<Op<Double>> tree = gene.toTreeNode();
//
//        MathExpr.rewrite(tree);
        writeOutput(output.toString(), file);
      }
    } catch (InterruptedException e) {
      Thread.currentThread()
          .interrupt();
      e.printStackTrace();
    } catch (IOException | ExecutionException e) {
      e.printStackTrace();
    }
  }

  private static String evolveAsync(Regression<Double> problem, GraphMap connections, ExecutorService pool)
      throws InterruptedException, ExecutionException {
    List<Future<EvolutionResult<ProgramGene<Double>, Double>>> futures = new ArrayList<>(10);
    for (int i = 0; i < 10; i++) {
      futures.add(pool.submit(() -> {
        CellularEngine engine = new CellularEngine(connections, problem);
        return EvolutionStream.ofEvolution(() -> engine.start(connections.size(), 1), engine::evolve)
            .limit(30)
            .collect(EvolutionResult.toBestEvolutionResult());
      }));
    }

    List<EvolutionResult<ProgramGene<Double>, Double>> results = new ArrayList<>(10);
    for (int i = 0; i < futures.size(); i++) {
      results.add(futures.get(i)
          .get());
    }
    double avgFitness = results.stream()
        .mapToDouble(EvolutionResult::bestFitness)
        .average()
        .orElse(0);
    Phenotype<ProgramGene<Double>, Double> bestPhenotype = results.stream()
        .map(er -> er.bestPhenotype())
        .sorted()
        .toList()
        .get(0);
    Double bestFitness = bestPhenotype.fitness();
    TreeNode<Op<Double>> tree = bestPhenotype.genotype()
        .gene()
        .toTreeNode();

    MathExpr.rewrite(tree);

    return String.format("Structure: %s%n%nBest fitness: %.5f%nAverage fitness: %.5f%nBest individual: %s%n%n",
        connections.toString(), bestFitness, avgFitness, new MathExpr(tree).toString());
  }

  private static Regression<Double> generateProblem(File file) throws IOException {

    try (
        BufferedReader br = new BufferedReader(new InputStreamReader(new GZIPInputStream(new FileInputStream(file))))) {
      String header = br.readLine();

      String[] vars = header.split("\t");

      ArrayList<Op<Double>> terminals = new ArrayList<>(vars.length);
      for (int i = 0; i < vars.length - 1; i++) {
        terminals.add(Var.of(vars[i], i));
      }
      terminals.add(EphemeralConst.of(() -> random().nextDouble(10)));

      final String csvData = br.readAllAsString()
          .replace("\t", ", ");
      List<Sample<Double>> samples = Sample.parseDoubles(csvData);
      Codec<Tree<Op<Double>, ?>, ProgramGene<Double>> codec = Regression.codecOf(OPS, ISeq.of(terminals), 5,
          gt -> gt.gene()
              .size() < 50);
      return Regression.of(codec, Error.of(LossFunction::mse), samples);
    }
  }

  private static void writeOutput(String output, File file) throws IOException {
    File dataDir = new File("outputs");

    if (!dataDir.exists() && !dataDir.mkdir()) {
      throw new IOException("Impossible to craete 'data' foler");
    }

    File outFile = new File(dataDir, file.getName());
    if (!outFile.exists() && !outFile.createNewFile()) {
      throw new IOException(String.format("Impossible to create '%s'", file));
    }
    try (BufferedWriter bw = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(outFile)))) {
      bw.write(output);
    }

  }
}
