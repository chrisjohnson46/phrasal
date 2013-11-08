package edu.stanford.nlp.mt.lm;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.util.concurrent.atomic.AtomicInteger;

import edu.stanford.nlp.mt.base.IString;
import edu.stanford.nlp.mt.base.Sequence;
import edu.stanford.nlp.mt.base.TokenUtils;

/**
 * KenLanguageModel - KenLM language model support via JNI.
 * 
 * @author daniel cer (danielcer@stanford.edu)
 * @author Spence Green
 *
 */
public class KenLanguageModel implements LanguageModel<IString> {
  
  static {
    System.loadLibrary("PhrasalKenLM");
  }
  
  private static final ByteOrder NATIVE_ORDER = ByteOrder.nativeOrder();
  
  /**
   * The efficiency of the LM queries is dependent on the speed with which
   * the LM contexts can be returned from JNI. Presently, we create a fixed-size
   * pool of byte arrays that are re-used across queries. allocateDirect() is extremely
   * slow, so the pool is created at initialization. However, this means that buffers
   * are subject to race conditions in the multi-threaded case. Therefore, we create
   * a ring-buffer of size POOL_MULTIPLIER * numThreads with the assumption that no
   * particular process is fast enough to occupy the whole ring-buffer. This is empirically
   * true, but means that the possibility of a race condition still exists.
   * 
   * Don't change the JNI query interface (scoreNGram) without profiling. The obvious stuff is
   * slow. Trust me.
   */
  private static final int POOL_MULTIPLIER = 3;
  
  private final String name;
  private final int order;
  private final long kenLMPtr;
  private final ByteBuffer[] stateBuffers;
  private int[] istringIdToKenLMId;
 
  private final AtomicInteger bufferPtr;
  
  // JNI methods
  private native long readKenLM(String filename);
  private native double scoreNGram(long kenLMPtr, int[] ngram, ByteBuffer stateBuf);
  private native int getId(long kenLMPtr, String token);
  private native int getOrder(long kenLMPtr);
  private native int getMaxOrder(long kenLMPtr);

  /**
   * Constructor for single-threaded usage.
   * 
   * @param filename
   */
  public KenLanguageModel(String filename) {
    this(filename,1);
  }
  
  /**
   * Constructor for multi-threaded queries.
   * 
   * @param filename
   * @param numThreads
   */
  public KenLanguageModel(String filename, int numThreads) {
    name = String.format("KenLM(%s)", filename);
    System.err.printf("KenLM: Reading %s (%d threads)%n", filename, numThreads);
    if (0 == (kenLMPtr = readKenLM(filename))) {
      File f = new File(filename);
      if (!f.exists()) {
        new RuntimeException(String.format("Error loading %s - file not found", filename));
      } else {
        new RuntimeException(String.format("Error loading %s - file is likely corrupt or created with an incompatible version of kenlm", filename));
      } 
    }
    order = getOrder(kenLMPtr);
    int maxOrder = getMaxOrder(kenLMPtr);
    int sizeofInt = Integer.SIZE / Byte.SIZE;
    stateBuffers = new ByteBuffer[numThreads * POOL_MULTIPLIER];
    for (int i = 0; i < stateBuffers.length; ++i) {
      stateBuffers[i] = ByteBuffer.allocateDirect((maxOrder-1)*sizeofInt);
    }
    bufferPtr = new AtomicInteger();
    initializeIdTable();
  }
  
  private void initializeIdTable() {
    // Don't remove this line!! Sanity check to make sure that start and end load before
    // building the index.
    System.err.printf("Special tokens: start: %s  end: %s%n", TokenUtils.START_TOKEN.toString(),
        TokenUtils.END_TOKEN.toString());
    istringIdToKenLMId = new int[IString.index.size()];
    for (int i = 0; i < istringIdToKenLMId.length; ++i) {
      istringIdToKenLMId[i] = getId(kenLMPtr, IString.index.get(i));
    }
  }
  
  private void updateIdTable() {
    int[] newTable = new int[IString.index.size()];
    System.arraycopy(istringIdToKenLMId, 0, newTable, 0, istringIdToKenLMId.length);
    for (int i = istringIdToKenLMId.length; i < newTable.length; ++i) {
      newTable[i] = getId(kenLMPtr, IString.index.get(i));
    }
    istringIdToKenLMId = newTable;
  }

  /**
   * This must be a synchronized call for the case in which the IString
   * vocabulary changes and we need to extend the lookup table.
   * 
   * @param token
   * @return
   */
  private synchronized int toKenLMId(IString token) {
    if (token.id >= istringIdToKenLMId.length) {
      updateIdTable();
    }
    return istringIdToKenLMId[token.id];
  }
  
  private static <T> Sequence<T> clipNgram(Sequence<T> sequence, int order) {
    int sequenceSz = sequence.size();
    int maxOrder = (order < sequenceSz ? order : sequenceSz);
    return sequenceSz == maxOrder ? sequence :
      sequence.subsequence(sequenceSz - maxOrder, sequenceSz);
  }
  
  private int[] toKenLMIds(Sequence<IString> ngram) {
    int[] ngramIds = new int[ngram.size()];
    for (int i = 0; i < ngramIds.length; i++) {
      // Notice: ngramids are in reverse order vv. the Sequence
      ngramIds[ngramIds.length-1-i] = toKenLMId(ngram.get(i));
    }
    return ngramIds;
  }

  @Override
  public LMState score(Sequence<IString> sequence) {
    Sequence<IString> boundaryState = ARPALanguageModel.isBoundaryWord(sequence);
    if (boundaryState != null) {
      return new KenLMState(0.0, toKenLMIds(boundaryState));
    }
    Sequence<IString> ngram = clipNgram(sequence, order);
    int[] ngramIds = toKenLMIds(ngram);
    final int bufferIdx = stateBuffers.length > 1 ? bufferPtr.getAndIncrement() % stateBuffers.length : 0;
    ByteBuffer stateBuffer = stateBuffers[bufferIdx];
    double score = scoreNGram(kenLMPtr, ngramIds, stateBuffer);
    IntBuffer contextBuffer = stateBuffer.order(NATIVE_ORDER).asIntBuffer();
    int[] context = new int[contextBuffer.limit()];
    contextBuffer.get(context);
    KenLMState state = new KenLMState(score, context);
    return state;
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
    return order;
  }
}