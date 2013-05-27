package com.mmmthatsgoodcode.hesperides.transform.impl;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang.ClassUtils;
import org.apache.commons.lang.StringUtils;
import org.objenesis.Objenesis;
import org.objenesis.ObjenesisStd;
import org.objenesis.instantiator.ObjectInstantiator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.esotericsoftware.reflectasm.ConstructorAccess;
import com.esotericsoftware.reflectasm.FieldAccess;
import com.mmmthatsgoodcode.hesperides.annotation.HBean;
import com.mmmthatsgoodcode.hesperides.annotation.HBeanGetter;
import com.mmmthatsgoodcode.hesperides.annotation.HBeanSetter;
import com.mmmthatsgoodcode.hesperides.annotation.Id;
import com.mmmthatsgoodcode.hesperides.annotation.Ignore;
import com.mmmthatsgoodcode.hesperides.core.Hesperides;
import com.mmmthatsgoodcode.hesperides.core.Node;
import com.mmmthatsgoodcode.hesperides.core.NodeImpl;
import com.mmmthatsgoodcode.hesperides.core.TransformationException;
import com.mmmthatsgoodcode.hesperides.core.Transformer;
import com.mmmthatsgoodcode.hesperides.transform.TransformerRegistry;


/**
 * A transformer that may take the com.mmmthatsgoodcode.hesperides.annotation Annotations in to account when transforming Objects to Nodes
 * 
 * It uses a combination of these 4 strategies to a) extract as much state from your Objects as possible b) instantiate your Objects
 * 1) Types annotated with @HBean will be reflected on to invoke their getXXX setXXX methods to extract and restore a persisted Object's state
 * 2) When there is an @HConstructor annotated constructor, @HConstructorField(name=fieldName) annotations on its arguments will be used to create your object. Since this does not help with extracting object state, it may ( should ) be used in combination with @HBean to provide access to non-public or any field that is on the constructors argumen list. Otherwise, the object's public fields will be persisted only ( via reflection ) and any field on the argument list of the @HConstructor that was not public at the time of transformation will be null
 * 3) A no-arg constructor and getting/setting public fields via reflection
 * 4) Failing all the above, Objenesis to instantiate without a no-arg constructor and getting/setting public fields
 * 
 * @author andras
 *
 * @param <T> Type of the Object being transformed to a Node
 */
public class AnnotatedObjectTransformer<T> implements Transformer<T> {

	private static final Logger LOG = LoggerFactory.getLogger(AnnotatedObjectTransformer.class);
	
	public Node transform(T object) throws TransformationException {
						
		LOG.trace("Transforming object {} to Node", object.getClass());
		
		Node node = new NodeImpl<String, T>();
		
		if (object == null) {
			node.setNullValue();
			return node;
		}
		node.setRepresentedType(object.getClass());

		List<Field> fields = getAllFields(object.getClass());

		// is this type marked @HBean ?
		if (object.getClass().getAnnotation(HBean.class) != null) {
			LOG.trace("Type is @HBean annotated");
			// yes! get fields and invoke getters
			
			// first, invoke explicitly ( @HBeanGetter ) specified getters
			for (Method method:getAllMethods(object.getClass())) {
				
				String field = null;
				try {
					
					HBeanGetter getterAnnotation = method.getAnnotation(HBeanGetter.class);
					if (getterAnnotation != null) {
						
						method.setAccessible(true);
						
						field = getterAnnotation.field();
						Field actualField = null;
						try {
							actualField = object.getClass().getField(field);
							fields.remove( actualField ); // remove this field from fields

						} catch (NoSuchFieldException e) {
							LOG.debug("Field {} is private or does not exist on {}", field, object.getClass().getSimpleName());
						}
						
						Object fieldValue =  method.invoke(object, (Object[])null) ;

						Node childNode = null;
						if (actualField != null) childNode = TransformerRegistry.getInstance().get(actualField).transform(fieldValue);
						else childNode = TransformerRegistry.getInstance().get(fieldValue.getClass()).transform(fieldValue);
						
						childNode.setName(Hesperides.Hints.STRING, field);
						node.addChild(childNode);
						
					}
					
				} catch (SecurityException | IllegalAccessException | IllegalArgumentException | InvocationTargetException e) {
					throw new TransformationException("Could not invoke annotated getter "+method, e);
					
				}
				
			}
			
			// see if there are getFieldName methods for the remaining fields
			for (Iterator<Field> iterator = fields.iterator(); iterator.hasNext(); ) {
				Field field = iterator.next();
				
				try {
					Method getter = object.getClass().getMethod("get"+StringUtils.capitalize(field.getName()), (Class<?>[])null);
					iterator.remove();
					
					Node childNode = TransformerRegistry.getInstance().get(field).transform( getter.invoke(object, (Object[])null) );
					childNode.setName(Hesperides.Hints.STRING, field.getName());
					node.addChild(childNode);
					
				} catch (NoSuchMethodException e) {
					// nope
					
				} catch (SecurityException | IllegalAccessException | IllegalArgumentException | InvocationTargetException e) {
					// could not invoke getter
					throw new TransformationException("Could not invoke getter", e);
				}
				
			}
			
			if (fields.size() > 0) LOG.debug("{} fields were not accessible via getters", fields.size());
			
		} 
		
		// TODO parameter to HBean that sets if we should continue here
		// reflect on fields we still have to reflect on
	
		for (Field field:fields) {
			try {
				LOG.trace("Looking at field {} with value {}", field.getName(), field.get(object));
				field.setAccessible(true);

				// see if this is an @Ignore 'd field
				if (field.getAnnotation(Ignore.class) == null) {
					
					// this is something we'll have to work with
					Node childNode;
					
					// see if this is an @Id field
					if (field.getAnnotation(Id.class) != null) {
						LOG.trace("Field {} is an @Id field", field.getName());
						int idFieldTypeHint = Hesperides.Hints.typeToHint(field.getType());
						if (idFieldTypeHint == Hesperides.Hints.STRING) node.setName(idFieldTypeHint, field.get(object));
						else throw new TransformationException("Id field can only be String"); // TODO add a constraint to the annotation ?
					}

					childNode = TransformerRegistry.getInstance().get(field).transform(field.get(object));				
					childNode.setName(Hesperides.Hints.STRING, field.getName());
					node.addChild(childNode);
				
				} else {
					LOG.trace("Field {} is an @Ignored field, skipping.", field.getName());
				}

			
			} catch (SecurityException | IllegalArgumentException | IllegalAccessException e) {
				// could not access field
				LOG.debug("Caught exception while reflecting on field {} : {}", field, e);
			}
		}
		
		
		
		return node;
	}

	
	public T transform(Node<? extends Object, T> node) throws TransformationException {
		
		T instance = null;
			try {
				if (node.getValueHint() == Hesperides.Hints.NULL) return null;
				
				Class type = node.getRepresentedType();
				LOG.trace("Trasforming Node to an instance of {}", type);
				
				if (type.isPrimitive()) type = ClassUtils.primitiveToWrapper(type); // convert a primitive to its Class

				/* Create instance of represented type
				--------------------------------------- */
				
				// see if there is a constructor marked with @HConstructor
				if (false) {
					
				} else {
				
					// fall back to using the no-arg constructor and use reflection to set fields
					
					try {
						ConstructorAccess<T> constructor = ConstructorAccess.get(type);
						instance = constructor.newInstance();
					} catch(RuntimeException e) {
						// ReflectASM failed to instantiate, lets try with skipping the constructor
						Objenesis objenesis = new ObjenesisStd();
						ObjectInstantiator instantiator = objenesis.getInstantiatorOf(type);
						instance = (T) instantiator.newInstance();
					}
					
				}
					
				/* Start settings Fields
				------------------------- */
				
				
				
				List<Node> totalChildren = new ArrayList<Node>(node.getChildren());
				// see if the type is @HBean annotated
				
				if (type.getAnnotation(HBean.class) != null) {
					
					// yes! use setters
					
					// invoke explicitly ( @HBeanSetter ) marked setters first
					for (Method method:getAllMethods(type)) {

						String field = null;
						try {
							
							HBeanSetter setterAnnotation = method.getAnnotation(HBeanSetter.class);
							if (setterAnnotation != null) {
								
								method.setAccessible(true);
								
								field = setterAnnotation.field();
								
								// see if there is a similarly named child node on this Node
								Node fieldNode = node.getChild(field);
								if (fieldNode != null) {
									// there is
									
									// get the field matching the attribute on the setter ( there might be none )
									Field actualField = null;
									try {
										actualField = type.getField(field);
	
									} catch (NoSuchFieldException e) {
										LOG.debug("Field {} is private or does not exist on {}", field, type.getSimpleName());
									}
									
									LOG.debug("Invoking {} with {}", method, fieldNode);
									// invoke setter
									if (actualField != null) method.invoke(instance, TransformerRegistry.getInstance().get(actualField).transform(fieldNode));
									else method.invoke(instance, TransformerRegistry.getInstance().get(fieldNode.getRepresentedType()).transform(fieldNode));
											
									totalChildren.remove(fieldNode); // mark child as processed

								} else {
									// nope.. not much we can do
									LOG.debug("Found setter for field {} but no matching (String id'd) child is available on this Node!", field);
								}

								
							}
							
						} catch (SecurityException | IllegalAccessException | IllegalArgumentException | InvocationTargetException e) {
							throw new TransformationException("Could not invoke annotated setter "+method+" possibly type mismatch with field", e);
							
						}
						
					}					
					
					LOG.trace("Looking for set(FieldName) methods..");
					
					// try to find setters for the remaining fields
					for(Iterator<Node> iterator = totalChildren.iterator(); iterator.hasNext(); ) {
						Node fieldNode = iterator.next();
						String fieldName = (String) fieldNode.getName();
						String setterName = "set"+StringUtils.capitalize(fieldName);
						LOG.debug("Trying to find setter {}, with {} parameter",setterName, fieldNode.getRepresentedType());

						try {
							Method setter = type.getMethod(setterName, fieldNode.getRepresentedType());
							
							// get the field matching the attribute on the setter ( there might be none )
							Field actualField = null;
							try {
								actualField = type.getField((String) fieldNode.getName());
							} catch (NoSuchFieldException e) {
								LOG.debug("Field {} is private or does not exist on {}", fieldName, type.getSimpleName());
							}
							
							if (actualField!=null) setter.invoke(instance, TransformerRegistry.getInstance().get(actualField).transform(fieldNode));
							else setter.invoke(instance, TransformerRegistry.getInstance().get(fieldNode.getRepresentedType()).transform(fieldNode));
								
							iterator.remove();
						} catch (NoSuchMethodException e) {
							// nope, no setter
							LOG.debug("No method {}, with {} parameter",setterName, fieldNode.getRepresentedType());
						} catch (SecurityException | IllegalArgumentException | InvocationTargetException e) {
							throw new TransformationException("Could not invoke setter", e);
						}
						
					}
					
					if (totalChildren.size() > 0) LOG.debug("{} fields were not accessible via setters", node.getChildren().size());
					
				}
				
				// use reflection to set remaining fields
					
				for(Node fieldNode:totalChildren) {
					Class fieldNodeType = fieldNode.getRepresentedType();
					LOG.trace("Trying to set {}", fieldNode);

					try {
						Field field = type.getField((String) fieldNode.getName());

						field.setAccessible(true);
						
						field.set(instance, TransformerRegistry.getInstance().get(field).transform(fieldNode));
						
					} catch (SecurityException e) {
						throw new TransformationException("SecurityException caught while trying to set field "+fieldNode.getName()+" accessible on "+type.getSimpleName(), e);
					} catch (NoSuchFieldException e) {
						throw new TransformationException("Field "+fieldNode.getName()+" does not exist on "+type.getSimpleName());
					}
					
				}
				
				
				
				
				
			} catch ( IllegalAccessException e ) {
				throw new TransformationException(e);
			}
		
		
		return instance;
		
	}
	
	private List<Field> getAllFields(Class clazz) {
		
		List<Field> fields = new ArrayList<Field>();
		
		fields.addAll(Arrays.asList(clazz.getDeclaredFields()));
		
		Class superClass = clazz.getSuperclass();
		if (superClass != null) fields.addAll(getAllFields(superClass));
		
		return fields;
		
	}
	
	private List<Method> getAllMethods(Class clazz) {
		
		List<Method> methods = new ArrayList<Method>();
		
		methods.addAll(Arrays.asList(clazz.getDeclaredMethods()));
		
		Class superClass = clazz.getSuperclass();
		if (superClass != null) methods.addAll(getAllMethods(superClass));
		
		return methods;
		
	}

}
