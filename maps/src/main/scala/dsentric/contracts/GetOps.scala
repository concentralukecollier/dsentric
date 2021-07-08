package dsentric.contracts

import cats.data.NonEmptyList
import dsentric.codecs.std.DCodecs
import dsentric.{Available, DObject, Failed, Found, NotFound, Path, Raw, RawArray, RawObject, Valid}
import dsentric.codecs.{DCodec, DCollectionCodec, DContractCodec, DCoproductCodec, DMapCodec, DProductCodec, DTypeContractCodec, DValueClassCodec, DValueCodec}
import dsentric.failure.{AdditionalElementFailure, ClosedContractFailure, ContractTypeResolutionFailure, CoproductTypeValueFailure, Failure, IncorrectKeyTypeFailure, IncorrectTypeFailure, MissingElementFailure, ValidResult}
import shapeless.{HList, HNil}

import scala.collection.mutable
import scala.collection.mutable.ListBuffer

/**
 * Validates and returns the element, bad values can be removed or generate failures.
 * Empty objects or Null values are left
 */
private[dsentric] trait GetOps {

  /**
   * Validates Types and structure, applies any defaults if empty
   * */
  def get[D <: DObject](baseContract:BaseContract[D], obj:RawObject, badTypes:BadTypes):Valid[RawObject] = {
    def getAdditionalProperties:ValidResult[RawObject] = {
      val exclude = baseContract._fields.keySet
      baseContract match {
        case a:AdditionalProperties[Any, Any]@unchecked =>
          val (baseObject, additionalObject) = obj.partition(p => exclude(p._1))
          val codec = DCodecs.keyValueMapCodec(a._additionalKeyCodec, a._additionalValueCodec)
          getMap(a._root, a._path, badTypes, codec, additionalObject) match {
            case Found(t) =>
              val rawObject = codec.apply(t)
              if (rawObject == additionalObject)
                ValidResult.success(obj)
              else
                ValidResult.success(baseObject ++ rawObject)
            case NotFound =>
              ValidResult.success(baseObject)
            case Failed(head, tail) =>
              ValidResult.failure(head, tail)
          }
        case _ if badTypes == DropBadTypes =>
          ValidResult.success(obj.view.filterKeys(exclude).toMap)
        case _ =>
          obj.keys
            .filterNot(exclude)
            .map(k => ClosedContractFailure(baseContract._root, baseContract._path, k))
            .toList match {
            case head :: tail =>
              ValidResult.failure(head, tail)
            case Nil =>
              ValidResult.success(obj)
          }
      }
    }

    val drop = badTypes.nest == DropBadTypes
    baseContract._fields.foldLeft(getAdditionalProperties){
      case (Right(d), (_, p)) =>
        p.__apply(d, drop)
      case (l@Left(nel), (_, p)) =>
        p.__apply(obj, drop) match {
          case Right(_) =>
            l
          case Left(nel2) =>
            Left(nel ::: nel2)
        }
    } match {
      case Right(obj) =>
        Found(obj)
      case Left(NonEmptyList(head, tail)) =>
        Failed(head, tail)
    }
  }

  def get[D <: DObject, T](propertyLens: ValuePropertyLens[D, T], raw:Raw, badTypes: BadTypes):Available[T] =
    getCodec(propertyLens._root, propertyLens._path, badTypes)(propertyLens._codec -> raw)


  protected def getCodec[D <: DObject, C](contract:ContractFor[D], path:Path, badTypes:BadTypes):Function[(DCodec[C], Raw), Available[C]] = {
    case (d:DValueCodec[C], raw) =>
      getValue(contract, path, badTypes, d, raw)
    case (d:DMapCodec[C, _, _], rawObject:RawObject@unchecked) =>
      getMap(contract, path, badTypes, d, rawObject)
    case (d:DCollectionCodec[C, _], rawArray: RawArray@unchecked) =>
      getCollection(contract, path, badTypes, d, rawArray)
    case (d:DContractCodec[_], rawObject:RawObject@unchecked) =>
      getContract(contract, path, badTypes, d.contract, d.cstr, rawObject)
        .asInstanceOf[Available[C]]
    case (d:DTypeContractCodec[_], rawObject:RawObject@unchecked) =>
      getTypeContract(contract, path, badTypes, d.contracts, d.cstr, rawObject)
        .asInstanceOf[Available[C]]
    case (d:DValueClassCodec[C, _], raw) =>
      getValueClass(contract, path, badTypes, d, raw)
    case (d:DProductCodec[C, _, _], rawArray:RawArray@unchecked) =>
      getProduct(contract, path, badTypes, d, rawArray)
    case (d:DCoproductCodec[C, _], raw) =>
      getCoproduct(contract, path, badTypes, d, raw)
    case _ if badTypes == DropBadTypes =>
      NotFound
    case (d, raw) =>
      Failed(IncorrectTypeFailure(contract, path, d, raw))
  }

  protected def getValue[D <: DObject, V](contract:ContractFor[D], path:Path, badTypes:BadTypes, codec:DValueCodec[V], raw:Raw):Available[V] =
    codec.unapply(raw) match {
      case None if badTypes == DropBadTypes =>
        NotFound
      case None =>
        Failed(IncorrectTypeFailure(contract, path, codec, raw))
      case Some(v) =>
        Found(v)
    }

  protected def getMap[D <: DObject, C, K, V](contract:ContractFor[D], path:Path, badTypes:BadTypes, codec:DMapCodec[C, K, V], raw:RawObject):Available[C] = {
    val nested = badTypes.nest
    raw.view.map { p =>
      val key =
        codec.keyCodec.unapply(p._1) match {
          case None if nested == DropBadTypes =>
            NotFound
          case None =>
            Failed(IncorrectKeyTypeFailure(contract, path, codec.keyCodec, p._1))
          case Some(key) =>
            Found(key)
        }
      val value = getCodec(contract, path \ p._1, nested)(codec.valueCodec -> p._2)
      Available.sequence2(key, value)
    }.foldLeft[Either[ListBuffer[Failure], mutable.Builder[(K, V), Map[K, V]]]](Right(Map.newBuilder[K, V])) {
      case (Right(mb), Found(pair)) =>
        Right(mb.addOne(pair))
      case (Right(_), Failed(head, tail)) =>
        Left(new ListBuffer[Failure].addAll(head :: tail))
      case (Left(lb), Failed(head, tail)) =>
        Left(lb.addAll(head :: tail))
      case (result, _) =>
        result
    } match {
      case Right(mb) =>
        val newMap = mb.result()
        codec.build(newMap) match {
          case Some(m) =>
            Found(m)
          case None if badTypes == DropBadTypes =>
            NotFound
          case None =>
            Failed(IncorrectTypeFailure(contract, path, codec, raw))
        }
      case Left(_) if badTypes == DropBadTypes =>
        NotFound
      case Left(lb) =>
        val head :: tail = lb.result()
        Failed(head, tail)
    }
  }

  protected def getCollection[D <: DObject, S, T](contract:ContractFor[D], path:Path, badTypes:BadTypes, codec:DCollectionCodec[S, T], raw:RawArray):Available[S] = {
    val nested = badTypes.nest
    raw.zipWithIndex.map { p =>
      getCodec(contract, path \ p._2, nested)(codec.valueCodec -> p._1) -> p._2
    }.foldLeft[Either[ListBuffer[Failure], mutable.Builder[T, Vector[T]]]](Right(Vector.newBuilder[T])){
      case (Right(vb), (Found(t), _)) =>
        Right(vb.addOne(t))
      case (Right(_), (Failed(head, tail), _)) =>
        Left(new ListBuffer[Failure].addAll(head :: tail))
      case (Left(lb), (Failed(head, tail), _)) =>
        Left(lb.addAll(head :: tail))
      case (Right(_), (NotFound, index)) =>
        Left(new ListBuffer[Failure].addOne(MissingElementFailure(contract, codec, path \ index)))
      case (Left(lb), (NotFound, index)) =>
        Left(lb.addOne(MissingElementFailure(contract, codec, path \ index)))
      case (result, _) =>
        result
    } match {
      case Right(vb) =>
        val newVector = vb.result()
        codec.build(newVector) match {
          case Some(m) =>
            Found(m)
          case None if badTypes == DropBadTypes =>
            NotFound
          case None =>
            Failed(IncorrectTypeFailure(contract, path, codec, raw))
        }
      case Left(_) if badTypes == DropBadTypes =>
        NotFound
      case Left(lb) =>
        val head :: tail = lb.result()
        Failed(head, tail)
    }
  }

  protected def getContract[D <: DObject, D2 <: DObject](contract:ContractFor[D], path:Path, badTypes:BadTypes, d2Contract:ContractFor[D2], d2Cstr:RawObject => D2, raw:RawObject):Available[D2] =
    d2Contract.__get(raw, badTypes.nest == DropBadTypes) match {
      case Found(rawObject) =>
        Found(d2Cstr(rawObject))
      case _:Failed if badTypes == DropBadTypes =>
        NotFound
      case f:Failed =>
        f.rebase(contract, path)
    }


  protected def getTypeContract[D <: DObject, D2 <: DObject](contract:ContractFor[D], path:Path, badTypes:BadTypes, typeCodecs:PartialFunction[D2, ContractFor[D2]], d2Cstr:RawObject => D2, raw:RawObject):Available[DObject] =
    typeCodecs.lift(d2Cstr(raw)) match {
      case None if badTypes == DropBadTypes =>
        NotFound
      case None =>
        Failed(ContractTypeResolutionFailure(contract, path, raw))
      case Some(typeContract) =>
        getContract(contract, path, badTypes, typeContract, d2Cstr, raw)
    }

  protected def getValueClass[D <: DObject, T, S](contract:ContractFor[D],  path:Path, badTypes:BadTypes, codec:DValueClassCodec[T, S], raw:Raw): Available[T] = {
    getCodec(contract, path, badTypes)(codec.internalCodec -> raw) match {
      case Found(s) =>
        codec.to(s) match {
          case Some(t) =>
            Found(t)
          case None if badTypes == DropBadTypes =>
            NotFound
          case None =>
            Failed(IncorrectTypeFailure(contract, path, codec, raw))
        }
      case f =>
        f.asInstanceOf[Available[T]]
    }
  }

  protected def getProduct[D <: DObject, T, E <: HList, H <: HList](contract:ContractFor[D], path:Path, badTypes:BadTypes, codec:DProductCodec[T, E, H], raw:RawArray):Available[T] = {
    val nested = badTypes.nest
    val init =
      if (raw.size < codec.codecsArray.length) {
        val lb = new ListBuffer[Failure]
        for (i <- raw.size to codec.codecsArray.length)
          yield lb.addOne(MissingElementFailure(contract, codec, path \ i))
        Left(lb)
      }
      else
        Right(HNil)
    raw.zipWithIndex.map { p =>
      if (p._2 >= codec.codecsArray.length)
        Failed(AdditionalElementFailure(contract, path \ p._2)) -> p._2
      else
        getCodec(contract, path \ p._2, nested)(codec.codecsArray(p._2) -> p._1) -> p._2
    }.foldRight[Either[ListBuffer[Failure], HList]](init){
      case ((Found(t), _), Right(h)) =>
        Right(t :: h)
      case ((Failed(head, tail), _), Right(_)) =>
        Left(new ListBuffer[Failure].addAll(head :: tail))
      case ((Failed(head, tail), _), Left(lb)) =>
        Left(lb.addAll(head :: tail))
      case ((NotFound, index), Right(_)) =>
        Left(new ListBuffer[Failure].addOne(MissingElementFailure(contract, codec, path \ index)))
      case ((NotFound, index), Left(lb)) =>
        Left(lb.addOne(MissingElementFailure(contract, codec, path \ index)))
      case (_, result) =>
        result
    } match {
      case Right(h) =>
        codec.build(h.asInstanceOf[E]) match {
          case Some(m) =>
            Found(m)
          case None if badTypes == DropBadTypes =>
            NotFound
          case None =>
            Failed(IncorrectTypeFailure(contract, path, codec, raw))
        }
      case Left(_) if badTypes == DropBadTypes =>
        NotFound
      case Left(lb) =>
        val head :: tail = lb.result()
        Failed(head, tail)
    }
  }

  /**
   * Returns unavailable result of last entry (Right biased)
   * @param contract
   * @param path
   * @param badTypes
   * @param codec
   * @param raw
   * @tparam D
   * @tparam T
   * @tparam H
   * @return
   */
  protected def getCoproduct[D <: DObject, T, H <: HList](contract:ContractFor[D], path:Path, badTypes:BadTypes, codec:DCoproductCodec[T, H], raw:Raw): Available[T] = {
    codec.codecsList.foldLeft[Available[T]](NotFound){
      case (a:Found[T], _) => a
      case (a, c) =>
        getCodec(contract, path, badTypes)(c -> raw) match {
          case Found(t) => codec.lift(t, c).fold[Available[T]](NotFound)(Found(_))
          case NotFound => NotFound
          case f:Failed =>
            a match {
              case f2:Failed => f2 ++ f
              case _ => f
            }
        }
    } match {
      case Failed(head, tail) =>
        Failed(CoproductTypeValueFailure(contract, codec, path, head :: tail, raw))
      case a =>
        a
    }
  }


}

private[dsentric] object GetOps extends GetOps