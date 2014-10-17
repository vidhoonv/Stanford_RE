package edu.stanford.nlp.kbp.slotfilling.classify;

import edu.stanford.nlp.io.IOUtils;
import edu.stanford.nlp.kbp.slotfilling.common.Maybe;
import edu.stanford.nlp.kbp.slotfilling.common.RelationType;
import edu.stanford.nlp.kbp.slotfilling.common.SentenceGroup;
import edu.stanford.nlp.kbp.slotfilling.ir.KBPRelationProvenance;
import edu.stanford.nlp.math.ArrayMath;
import edu.stanford.nlp.stats.ClassicCounter;
import edu.stanford.nlp.stats.Counter;
import edu.stanford.nlp.stats.Counters;
import edu.stanford.nlp.util.CoreMap;
import edu.stanford.nlp.util.MetaClass;
import edu.stanford.nlp.util.Pair;

import java.io.*;
import java.util.*;

import static edu.stanford.nlp.util.logging.Redwood.Util.*;

/**
 * Common and static methods for a complete relation extraction classify.
 *
 * @author Gabor Angeli
 */
public abstract class RelationClassifier implements Serializable {
  protected Maybe<TrainingStatistics> statistics = Maybe.Nothing();
  
  /**
   * Generate a multinomial over relations P(relation | sentences).
   * Note that the NIL label is NOT generated by this method.
   *
   * Either this or ClassifyRelation should be overwritten.
   * By default, this normalizes the pointwise scores returned by
   * classifyRelation().
   *
   * @param input The featurized input
   * @param rawSentences The unfeaturized raw sentences, if available
   * @return A multinomial over possible relations P(relation | sentences)
   */
  // Fundamentally, still a distribution over relation types: Counter<String>
  public Counter<Pair<String, Maybe<KBPRelationProvenance>>> classifyRelations(SentenceGroup input, Maybe<CoreMap[]> rawSentences) {
    Counter<Pair<String, Maybe<KBPRelationProvenance>>> scores = new ClassicCounter<Pair<String, Maybe<KBPRelationProvenance>>>();
    for (RelationType rel : RelationType.values()) {
      Pair<Double, Maybe<KBPRelationProvenance>> piecewiseScore = classifyRelation(input, rel, rawSentences);
      if (piecewiseScore.first > 0.0) {
        scores.setCount(Pair.makePair(rel.canonicalName, piecewiseScore.second), piecewiseScore.first);
      }
    }
    Counters.normalize(scores);
    return scores;
  }

  /**
   * Make an individual judgement on whether a particular relation holds, given an input datum:
   * P(relation=r | sentences).
   *
   * Either this or ClassifyRelation should be overwritten.
   * By default, this scales the values from classifyRelations by the maximum
   * seen value, and outputs that as a score.
   *
   * @param input The featurized input
   * @param relation The relation to classify
   * @param rawSentences The unfeaturized raw sentences, if available
   * @return A score between 0 and 1 denoting P(relation=r | sentence)
   */
  public Pair<Double, Maybe<KBPRelationProvenance>> classifyRelation(SentenceGroup input, RelationType relation, Maybe<CoreMap[]> rawSentences) {
    Counter<Pair<String, Maybe<KBPRelationProvenance>>> scores = classifyRelations(input, rawSentences);
    Pair<String, Maybe<KBPRelationProvenance>> bestGuess = Counters.argmax(scores);
    double maxCount = scores.getCount(bestGuess);
    if (maxCount == 0.0) return Pair.makePair(0.0, Maybe.<KBPRelationProvenance>Nothing());
    for (Pair<String, Maybe<KBPRelationProvenance>> candidate : scores.keySet()) {
      if (candidate.first.equals(relation.canonicalName)) {
        return Pair.makePair(scores.getCount(candidate), candidate.second);
      }
    }
    return Pair.makePair(0.0, Maybe.<KBPRelationProvenance>Nothing());
  }


  public Counter<String> classifyRelationsNoProvenance(SentenceGroup input, Maybe<CoreMap[]> rawSentences) {
    Counter<Pair<String, Maybe<KBPRelationProvenance>>> counts = classifyRelations(input, rawSentences);
    Counter<String> justRelations = new ClassicCounter<String>();
    for (Map.Entry<Pair<String, Maybe<KBPRelationProvenance>>, Double> entry : counts.entrySet()) {
      justRelations.setCount(entry.getKey().first, entry.getValue());
    }
    return justRelations;
  }

  public abstract TrainingStatistics train(KBPDataset<String, String> trainSet);
  public abstract void load(ObjectInputStream in) throws IOException, ClassNotFoundException;
  public abstract void save(ObjectOutputStream out) throws IOException;
  
  public void save(String path) throws IOException {
    // make sure the directory specified by path exists
    int lastSlash = path.lastIndexOf(File.separator);
    if (lastSlash > 0) {
      File dir = new File(path.substring(0, lastSlash));
      if (! dir.exists())
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
    }
    
    ObjectOutputStream out = new ObjectOutputStream(new FileOutputStream(path));
    save(out);
    out.close();
  }
  
  protected final double softmax(double score, List<Double> scores, double gamma) {
    double [] scoreArray = new double[scores.size()];
    for(int i = 0; i < scoreArray.length; i ++)
      scoreArray[i] = gamma * scores.get(i);
    double logSoftmax = (gamma * score) - ArrayMath.logSum(scoreArray);
    return Math.exp(logSoftmax);
  }

  //
  // Static Methods
  //

  /** * Converts a KBPTuple into a list of feature sets for classification. */
  protected static List<Collection<String>> tupleToFeatureList(SentenceGroup tuple) {
    List<Collection<String>> mentions = new ArrayList<Collection<String>>();
    for(int i = 0; i < tuple.size(); i ++) {
      if( tuple.size() == 0) warn(YELLOW, "Classifying a realtion with no supporting evidence");
      mentions.add(tuple.get(i).asFeatures());
    }
    return mentions;
  }

  /**
   * Converts a Counter of relations as strings to a Counter of relations as RelationType objects.
   */
  protected static Counter<RelationType> asRelationTypeCounter(Counter<String> scores) {
    Counter<RelationType> copy = new ClassicCounter<RelationType>();
    for (String relationName : scores.keySet()) {
      copy.setCount(RelationType.fromString(relationName).orCrash(relationName), scores.getCount(relationName));
    }
    return copy;
  }

  protected
  static <E extends RelationClassifier> E load(String modelPath, Properties props, Class<E> extractor) throws IOException, ClassNotFoundException {
    startTrack("Loading model [" + extractor.getSimpleName() + "] from " + modelPath);
    log("opening input streams...");
    InputStream is = null;
    ObjectInputStream in = null;
    //noinspection EmptyCatchBlock
    try {
      is = IOUtils.getInputStreamFromURLOrClasspathOrFileSystem(modelPath);
      in = new ObjectInputStream(is);
    } catch (IOException e) {
      if (!HeuristicRelationExtractor.class.isAssignableFrom(extractor)) { throw e; }
    }
    log("constructing class via reflection...");
    MetaClass metaclass = new MetaClass(extractor);
    E ex = metaclass.createInstance(props);
    log("calling Class' load() method...");
    ex.load(in);
    if (is != null) { is.close(); }
    endTrack("Loading model [" + extractor.getSimpleName() + "] from " + modelPath);
    return ex;
  }
  
  public Maybe<TrainingStatistics> getTrainingStatistics() {
    return statistics;
  }


  public static Counter<Pair<String, Maybe<KBPRelationProvenance>>> firstProvenance(Counter<String> stringCounter, SentenceGroup input) {
    ClassicCounter<Pair<String, Maybe<KBPRelationProvenance>>> withProvenance = new ClassicCounter<Pair<String, Maybe<KBPRelationProvenance>>>();
    for (Map.Entry<String, Double> entry : stringCounter.entrySet()) {
      withProvenance.setCount(Pair.makePair(entry.getKey(), Maybe.Just(input.getProvenance(0))), entry.getValue());
    }
    return withProvenance;
  }
}
