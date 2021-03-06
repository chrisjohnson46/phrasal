package edu.stanford.nlp.mt.decoder.util;

import java.util.List;

import edu.stanford.nlp.mt.tm.ConcreteRule;
import edu.stanford.nlp.mt.util.Featurizable;
import edu.stanford.nlp.mt.util.Sequence;

/**
 * Specifies the output space of the decoder for force
 * decoding.
 * 
 * @author danielcer
 * @author Spence Green
 * 
 * @param <TK>
 */
public interface OutputSpace<TK, FV> {

  /**
   * Optionally specify the source translation sequence.
   * 
   * @param sourceSequence
   */
  public void setSourceSequence(Sequence<TK> sourceSequence);

  /**
   * Filter a grid of translation rules.
   * 
   * @param ruleGrid
   */
  public void filter(RuleGrid<TK,FV> ruleGrid);

  /**
   * Returns true if the derivation created by the concatenation of
   * the arguments is an allowable sequence.
   * 
   * @param featurizable
   * @param rule
   * @return
   */
  public boolean allowableContinuation(Featurizable<TK, FV> featurizable,
      ConcreteRule<TK,FV> rule);

  /**
   * Returns true if the featurizable contains an allowable translation.
   * 
   * @param featurizable
   * @return
   */
  public boolean allowableFinal(Featurizable<TK, FV> featurizable);

  /**
   * Return all allowable target sequences. A null return value has the semantics of
   * allowing all output sequences.
   * 
   * @return
   */
  public List<Sequence<TK>> getAllowableSequences();
}
