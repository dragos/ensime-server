package org.ensime.sexp

package object formats {
  def deserializationError(got: Sexp) =
    throw new DeserializationException("Didn't expect a " + got)
  def serializationError(msg: String) = throw new SerializationException(msg)
}

