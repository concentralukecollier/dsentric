package dsentric.contracts

import dsentric.{DCodec, DObject, DProjection, Path, Strictness}

private[contracts] trait BaseContract[D <: DObject] { self =>
  private var __fields:Map[String, Property[D, Any]] = _
  @volatile
  private var _bitmap0:Boolean = false

  def apply[R](f:this.type => R):R = f(this)

  //Used for nested new object
  protected implicit def selfRef:BaseContract[DObject] =
    this.asInstanceOf[BaseContract[DObject]]

  def _path:Path

  def _fields: Map[String, Property[D, Any]] =
    if (_bitmap0) __fields
    else {
      this.synchronized{
        __fields = this.getClass.getMethods.flatMap { m =>
          if (classOf[Property[D, _]].isAssignableFrom(m.getReturnType) && m.getTypeParameters.isEmpty && m.getParameterTypes.isEmpty)
            m.invoke(this) match {
              case prop: Property[D, Any]@unchecked =>
                Some(prop.__nameOverride.getOrElse(m.getName) -> prop)
              case _ =>
                None
            }
          else
            None
        }.toMap
        _bitmap0 = true
      }
      __fields
    }
  def _keys:Set[String] =
    _fields.keySet

  def $dynamic[T](field:String)(implicit codec:DCodec[T], strictness:Strictness):MaybeProperty[D, T] =
    new MaybeProperty[D, T](Some(field), this, codec, strictness, Seq.empty)

  def $$(projection:DProjection):DProjection =
    projection.nest(this._path)

  def $$(paths:Path*):DProjection =
    DProjection(paths:_*).nest(this._path)
}

object EmptyBaseContract extends BaseContract[Nothing] {
  def _path: Path = Path.empty
}

