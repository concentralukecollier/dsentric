package dsentric.operators

import dsentric.codecs.{DCodec, DCollectionCodec, DContractCodec, DCoproductCodec, DMapCodec, DProductCodec, DTypeContractCodec, DValueClassCodec}
import dsentric.contracts.{BaseContract, CustomPathSetter, DefaultProperty, ExpectedObjectProperty, MaybeExpectedObjectProperty, MaybeObjectProperty, PathSetter, Property}
import dsentric.{DObject, DObjectInst, Raw, RawArray, RawObject, RawObjectOps}

trait DataOperationOps{

  def sanitize[D <: DObject](contract:BaseContract[D]):PathSetter[D] =
    CustomPathSetter(
      transform(contract){
        case (s:Sanitizer[Any], _, maybeRaw) =>
          s.sanitize(maybeRaw)
      },
      { d =>
        val r = transform(contract) {
          case (s: Sanitizer[Any], _, maybeRaw) =>
            s.sanitize(maybeRaw)
        }(d)
        RawObjectOps
          .calculateDeltaRaw(d, r)
          .getOrElse(RawObject.empty)
      }
    )

  /**
   * Traverses the cntract and object structure and applies point transformations to the values of the properties dependent
   * on the partial function
   * This does not reduce empty objects or null values
   *
   * Could introduce a reduce empty object flag
   * @param contract
   * @param pf
   * @tparam D0
   * @return
   */
  def transform[D0 <: DObject](contract:BaseContract[D0])(pf:PartialFunction[(DataOperator[_], Property[_, _], Option[Raw]), Option[Raw]]):Function[RawObject, RawObject] = { d0 =>

    def objectTransform[D <: DObject](contract:BaseContract[D], rawObject:RawObject):Option[RawObject] = {
      def transformObjectValue[T](obj:RawObject, key:String, property:Property[D, T]):Option[RawObject] =  {
        val current = obj.get(key)
        property._dataOperators
          .map(d => (d, property, current))
          .collectFirst(pf)
          .filter(_ != current)
          .map{
            case None =>
              obj - key
            case Some(newValue) =>
              obj + (key -> newValue)
          }
      }

      def transformObjectValueWithDefault[T](obj:RawObject, key:String, property:Property[D, T], default:Raw):Option[RawObject] =  {
        val current = obj.getOrElse(key, default)
        property._dataOperators
          .map(d => (d, property, Some(current)))
          .collectFirst(pf)
          .filter(_ != current)
          .map{
            case None =>
              obj - key
            case Some(newValue) =>
              obj + (key -> newValue)
          }
      }

      def maybeObjectPropertyTransform(propertyContract:BaseContract[D], propertyObject:RawObject, key:String):Option[RawObject] =
        propertyObject.get(key) match {
          case Some(nested:RawObject@unchecked) =>
            objectTransform(propertyContract, nested)
              .map{newNested => propertyObject + (key -> newNested)}
          case _ =>
            None
        }

      def expectedObjectPropertyTransform(propertyContract:BaseContract[D], propertyObject:RawObject, key:String):Option[RawObject] =
        propertyObject.get(key) match {
          case Some(nested:RawObject@unchecked) =>
            objectTransform(propertyContract, nested)
              .map{newNested => propertyObject + (key -> newNested)}
          case _ =>
            objectTransform(propertyContract, RawObject.empty)
              .map{newNested => propertyObject + (key -> newNested)}
        }

      def transformObjectCodecs[T](rawObject:RawObject, key:String, codec:DCodec[T]):Option[RawObject] =
        rawObject.get(key)
          .flatMap(raw => transformCodec(raw -> codec))
          .map{newObject => rawObject + (key -> newObject)}

      def transformCodec[T]:Function[(Raw, DCodec[T]), Option[Raw]] = {
        case (nestedObject:RawObject@unchecked, DContractCodec(codecContract)) =>
          objectTransform(codecContract, nestedObject)
        case (nestedObject:RawObject@unchecked, d:DTypeContractCodec) =>
          d.contracts.lift(new DObjectInst(nestedObject)).flatMap{typeContract =>
            objectTransform(typeContract, nestedObject)
          }
        case (nestedObject:RawObject@unchecked, d:DMapCodec[T, _, _]) if d.containsContractCodec =>
          nestedObject.keys.foldLeft(Option.empty[RawObject]) { (maybeChangedObject, mapKey) =>
            transformObjectCodecs(maybeChangedObject.getOrElse(nestedObject), mapKey, d.valueCodec)
              .orElse(maybeChangedObject)
          }

        case (nestedArray: RawArray@unchecked, d:DCollectionCodec[T, _]) if d.containsContractCodec =>
          nestedArray.zipWithIndex.foldLeft(Option.empty[RawArray]){ case (maybeChangedArray, (raw, index)) =>
            transformCodec(raw -> d.valueCodec)
              .map{ newElement => maybeChangedArray.getOrElse(nestedArray).updated(index, newElement)}
              .orElse(maybeChangedArray)
          }
        case (raw:Raw, d:DValueClassCodec[T, _]) if d.containsContractCodec =>
          transformCodec(raw -> d.internalCodec)
        case (nestedArray: RawArray@unchecked, d:DProductCodec[T, _, _]) if d.containsContractCodec =>
          nestedArray.zipWithIndex.foldLeft(Option.empty[RawArray]){ case (maybeChangedArray, (raw, index)) =>
            if (index >= d.codecsArray.length) maybeChangedArray
            else
              transformCodec(raw -> d.codecsArray(index))
                .map{ newElement => maybeChangedArray.getOrElse(nestedArray).updated(index, newElement)}
                .orElse(maybeChangedArray)
          }
        case (raw:Raw, d:DCoproductCodec[T, _]) if d.containsContractCodec =>
          d.codecsList.flatMap{c =>
            c.unapply(raw).flatMap { t =>
              transformCodec(raw -> c.asInstanceOf[DCodec[Any]])
            }
          }.headOption
        case _ =>
          None
      }


      contract._fields.foldLeft[Option[RawObject]](None){
        case (maybeChange, (key, p:MaybeObjectProperty[D])) =>
          val transformedObject =
            transformObjectValue(maybeChange.getOrElse(rawObject), key, p)
              .orElse(maybeChange)

          maybeObjectPropertyTransform(p, transformedObject.getOrElse(rawObject), key)
            .orElse(transformedObject)

        case (maybeChange, (key, p:ExpectedObjectProperty[D])) =>
          val transformedObject =
            transformObjectValueWithDefault(maybeChange.getOrElse(rawObject), key, p, RawObject.empty)
              .orElse(maybeChange)
          expectedObjectPropertyTransform(p, transformedObject.getOrElse(rawObject), key)
            .orElse(transformedObject)

        case (maybeChange, (key, p:MaybeExpectedObjectProperty[D])) =>
          val transformedObject =
            transformObjectValueWithDefault(maybeChange.getOrElse(rawObject), key, p, RawObject.empty)
              .orElse(maybeChange)
          expectedObjectPropertyTransform(p, transformedObject.getOrElse(rawObject), key)
            .orElse(transformedObject)

        case (maybeChange, (key, p:DefaultProperty[D, _])) =>
          val maybeTransformed =
            transformObjectValueWithDefault(maybeChange.getOrElse(rawObject), key, p, p.__rawDefault)
              .orElse(maybeChange)
          transformObjectCodecs(maybeTransformed.getOrElse(rawObject), key, p._codec)
            .orElse(maybeTransformed)

        case (maybeChange, (key, p:Property[D, _])) =>
          val maybeTransformed =
            transformObjectValue(maybeChange.getOrElse(rawObject), key, p)
              .orElse(maybeChange)
          transformObjectCodecs(maybeTransformed.getOrElse(rawObject), key, p._codec)
            .orElse(maybeTransformed)
      }
    }

    objectTransform(contract, d0)
      .getOrElse(d0)
  }

}

object DataOperationOps extends DataOperationOps
