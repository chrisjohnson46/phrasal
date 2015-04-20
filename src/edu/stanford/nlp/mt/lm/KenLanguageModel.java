package edu.stanford.nlp.mt.lm;

import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

import edu.stanford.nlp.mt.util.IString;
import edu.stanford.nlp.mt.util.Sequence;
import edu.stanford.nlp.mt.util.TokenUtils;
import edu.stanford.nlp.mt.util.Vocabulary;

/**
 * KenLM language model support via JNI.
 *
 * @author daniel cer (danielcer@stanford.edu)
 * @author Spence Green
 * @author Kenneth Heafield
 *
 */
public class KenLanguageModel implements LanguageModel<IString> {

  private static final int[] EMPTY_INT_ARRAY = new int[0];
  public static final String KENLM_LIBRARY_NAME = "PhrasalKenLM";
  
  static {
    try {
      System.loadLibrary(KENLM_LIBRARY_NAME);
    
    } catch (java.lang.UnsatisfiedLinkError e) {
      System.err.println("KenLM has not been compiled!");
      e.printStackTrace();
      System.exit(-1);
    }
  }

  private KenLM model;

  private final String name;

  private AtomicReference<int[]> istringIdToKenLMId;

  private final ReentrantLock preventDuplicateWork = new ReentrantLock();

  /**
   * Constructor for multi-threaded queries.
   *
   * @param filename
   */
  public KenLanguageModel(String filename) {
    model = new KenLM(filename);
    name = String.format("KenLM(%s)", filename);
    initializeIdTable();
  }

  /**
   * Create the mapping between IString word ids and KenLM word ids.
   */
  private void initializeIdTable() {
    // Don't remove this line!! Sanity check to make sure that start and end load before
    // building the index.
    System.err.printf("Special tokens: start: %s  end: %s%n", TokenUtils.START_TOKEN.toString(),
        TokenUtils.END_TOKEN.toString());
    int[] table = new int[Vocabulary.systemSize()];
    for (int i = 0; i < table.length; ++i) {
      table[i] = model.index(Vocabulary.systemGet(i));
    }
    istringIdToKenLMId = new AtomicReference<int[]>(table);
  }

  /**
   * Maps the IString id to a kenLM id. If the IString
   * id is out of range, update the vocab mapping.
   * @param token
   * @return kenlm id of the string
   */
  private int toKenLMId(IString token) {
    {
      int[] map = istringIdToKenLMId.get();
      if (token.id < map.length) {
        return map[token.id];
      }
    }
    // Rare event: we have to expand the vocabulary.
    // In principle, this doesn't need to be a lock, but it does
    // prevent unnecessary work duplication.
    if (preventDuplicateWork.tryLock()) {
      // This thread is responsible for updating the mapping.
      try {
        // Maybe another thread did the work for us?
        int[] oldTable = istringIdToKenLMId.get();
        if (token.id < oldTable.length) {
          return oldTable[token.id];
        }
        int[] newTable = new int[Vocabulary.systemSize()];
        System.arraycopy(oldTable, 0, newTable, 0, oldTable.length);
        for (int i = oldTable.length; i < newTable.length; ++i) {
          newTable[i] = model.index(Vocabulary.systemGet(i));
        }
        istringIdToKenLMId.set(newTable);
        return newTable[token.id];
      } finally {
        preventDuplicateWork.unlock();
      }
    }
    // Another thread is working.  Lookup directly.
    return model.index(token.toString());
  }

  @Override
  public IString getStartToken() {
    return TokenUtils.START_TOKEN;
  }

  @Override
  public IString getEndToken() {
    return TokenUtils.END_TOKEN;
  }

  @Override
  public String getName() {
    return name;
  }

  @Override
  public int order() {
    return model.order();
  }

  @Override
  public LMState score(Sequence<IString> sequence, int startIndex, LMState priorState) {
    // Extract prior state
    int[] state = priorState == null ? EMPTY_INT_ARRAY : ((KenLMState) priorState).getState();
    int[] ngramIds = makeKenLMInput(sequence, state);

    // Reverse the start index for KenLM
    int kenLMStartIndex = ngramIds.length - state.length - startIndex - 1;

    // Execute the query (via JNI) and construct the return state
    long got = model.scoreSeqMarshalled(ngramIds, kenLMStartIndex);
    return new KenLMState(KenLM.scoreFromMarshalled(got), ngramIds, KenLM.rightStateFromMarshalled(got));
  }

  /**
   * Convert a Sequence and an optional state to an input for KenLM.
   *
   * @param sequence
   * @param priorState
   * @return
   */
  private int[] makeKenLMInput(Sequence<IString> sequence, int[] priorState) {
    final int sequenceSize = sequence.size();
    int[] ngramIds = new int[sequenceSize + priorState.length];
    if (priorState.length > 0) {
      System.arraycopy(priorState, 0, ngramIds, sequenceSize, priorState.length);
    }
    for (int i = 0; i < sequenceSize; i++) {
      // Notice: ngramids are in reverse order vv. the Sequence
      ngramIds[sequenceSize-1-i] = toKenLMId(sequence.get(i));
    }
    return ngramIds;
  }
}
