package cn.jailedbird.arouter.ksp.compiler

import cn.jailedbird.arouter.ksp.compiler.utils.*
import com.alibaba.android.arouter.facade.annotation.Autowired
import com.alibaba.android.arouter.facade.enums.RouteType
import com.alibaba.android.arouter.facade.enums.TypeKind
import com.google.devtools.ksp.processing.*
import com.google.devtools.ksp.symbol.*
import com.squareup.kotlinpoet.*
import com.squareup.kotlinpoet.ksp.KotlinPoetKspPreview
import com.squareup.kotlinpoet.ksp.toClassName
import com.squareup.kotlinpoet.ksp.writeTo

@KotlinPoetKspPreview
class AutowiredSymbolProcessorProvider : SymbolProcessorProvider {

    override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor {
        return AutowiredSymbolProcessor(
            KSPLoggerWrapper(environment.logger), environment.codeGenerator
        )
    }

    class AutowiredSymbolProcessor(
        private val logger: KSPLoggerWrapper,
        private val codeGenerator: CodeGenerator,
    ) : SymbolProcessor {
        @Suppress("SpellCheckingInspection")
        companion object {
            val AUTOWIRED_CLASS_NAME = Autowired::class.qualifiedName!!
            private val ISYRINGE_CLASS_NAME = Consts.ISYRINGE.quantifyNameToClassName()
            private val JSON_SERVICE_CLASS_NAME = Consts.JSON_SERVICE.quantifyNameToClassName()
            private val AROUTER_CLASS_NAME =
                ClassName("com.alibaba.android.arouter.launcher", "ARouter")
        }


        override fun process(resolver: Resolver): List<KSAnnotated> {
            val symbol = resolver.getSymbolsWithAnnotation(AUTOWIRED_CLASS_NAME)

            val elements = symbol
                .filterIsInstance<KSPropertyDeclaration>()
                .toList()

            if (elements.isNotEmpty()) {
                logger.info(">>> AutowiredSymbolProcessor init. <<<")
                try {
                    parseAutowired(elements)
                } catch (e: Exception) {
                    logger.exception(e)
                }
            }
            return emptyList()
        }

        private fun parseAutowired(elements: List<KSPropertyDeclaration>) {
            logger.info(">>> Found autowired field, start... <<<")
            generateAutowiredFiles(categories(elements))
        }

        private fun categories(elements: List<KSPropertyDeclaration>): MutableMap<KSClassDeclaration, MutableList<KSPropertyDeclaration>> {
            val parentAndChildren =
                mutableMapOf<KSClassDeclaration, MutableList<KSPropertyDeclaration>>()
            for (element in elements) {
                // Class of the member
                logger.check(element.parentDeclaration is KSClassDeclaration) {
                    "Property annotated with @Autowired 's enclosingElement(property's class) must be non-null!"
                }
                val parent = element.parentDeclaration as KSClassDeclaration

                if (element.modifiers.contains(Modifier.PRIVATE)) {
                    throw IllegalAccessException(
                        "The inject fields CAN NOT BE 'private'!!! please check field ["
                                + element.simpleName.asString() + "] in class [" + parent.qualifiedName?.asString() + "]"
                    )
                }
                if (parentAndChildren.containsKey(parent)) {
                    parentAndChildren[parent]!!.add(element)
                } else {
                    parentAndChildren[parent] = mutableListOf(element)
                }
            }
            logger.info("@Autowired categories finished.")
            return parentAndChildren
        }

        @Suppress("SpellCheckingInspection")
        private fun generateAutowiredFiles(parentAndChildren: MutableMap<KSClassDeclaration, MutableList<KSPropertyDeclaration>>) {
            /** private var serializationService: SerializationService? = null */
            val serializationServiceProperty = PropertySpec.builder(
                "serializationService",
                JSON_SERVICE_CLASS_NAME.copy(nullable = true),
                KModifier.PRIVATE,
            ).mutable(true)
                .initializer("null")
                .build()

            /** target: Any? */
            val parameterName = "target"
            val parameterSpec = ParameterSpec.builder(
                parameterName,
                Any::class.asTypeName().copy(nullable = true)
            ).build()

            for (entry in parentAndChildren) {
                val parent: KSClassDeclaration = entry.key
                val children: List<KSPropertyDeclaration> = entry.value
                if (children.isEmpty()) continue
                logger.info(">>> Start process " + children.size + " field in " + parent.simpleName.asString() + " ... <<<")
                /** override fun inject(target: Any?) */
                val injectMethodBuilder: FunSpec.Builder = FunSpec
                    .builder(Consts.METHOD_INJECT)
                    .addModifiers(KModifier.OVERRIDE)
                    .addParameter(parameterSpec)

                /** serializationService = ARouter.getInstance().navigation(SerializationService::class.java) */
                injectMethodBuilder.addStatement(
                    "serializationService = %T.getInstance().navigation(%T::class.java)",
                    AROUTER_CLASS_NAME,
                    JSON_SERVICE_CLASS_NAME
                )
                val parentClassName = parent.toClassName()
                val message =
                    "The target that needs to be injected must be ${parentClassName.simpleName}, please check your code!"
                injectMethodBuilder.addStatement(
                    "val substitute = (target as? %T)?: throw IllegalStateException(\n·%S\n·)",
                    parentClassName, message
                )

                val parentRouteType = parent.routeType

                // Generate method body, start inject.
                for (child in children) {
                    if (child.isSubclassOf(Consts.IPROVIDER)) { // It's provider, inject by IProvider
                        addProviderStatement(child, injectMethodBuilder, parentClassName)
                    } else { // It's normal intent value (activity or fragment)
                        addActivityOrFragmentStatement(
                            child, injectMethodBuilder, TypeKind.values()[child.typeExchange()],
                            parentRouteType, parentClassName
                        )
                    }
                }

                val autowiredClassName = parent.simpleName.asString() + Consts.NAME_OF_AUTOWIRED
                val qualifiedName = parent.qualifiedName!!.asString()
                val packageName = qualifiedName.substring(0, qualifiedName.lastIndexOf("."))

                val file =
                    FileSpec.builder(packageName, autowiredClassName)
                        .addImport("android.util", "Log") // manual import (without %T)
                        .addImport("com.alibaba.android.arouter.facade.model", "TypeWrapper")
                        .addType(
                            TypeSpec.classBuilder(ClassName(packageName, autowiredClassName))
                                .addKdoc(Consts.WARNING_TIPS)
                                .addSuperinterface(ISYRINGE_CLASS_NAME)
                                .addProperty(serializationServiceProperty)
                                .addFunction(injectMethodBuilder.build())
                                .build()
                        )
                        .build()

                // Get input source (@Autowired) which gene the output file
                val dependency = mutableSetOf<KSFile>()
                parent.containingFile?.let {
                    dependency.add(it)
                }
                /**
                 *  Judge this file generate with isolating or aggregating mode
                 *  More detail: https://kotlinlang.org/docs/ksp-incremental.html#symbolprocessorprovider-the-entry-point
                 *  this beanch, I test @Autowired, it is a completely isolating mode processor
                 *  https://github.com/JailedBird/ARouter/tree/test-autowired-incremental
                 *  */
                file.writeTo(codeGenerator, false, dependency)
                logger.info(">>> " + parent.simpleName.asString() + " has been processed, " + autowiredClassName + " has been generated. <<<")
            }
            logger.info(">>> Autowired processor stop. <<<")
        }

        /**
         * Inject Provider field, such as
         * [substitute.helloService = ARouter.getInstance().navigation(HelloService::class.java)]
         * */
        private fun addProviderStatement(
            ksPropertyDeclaration: KSPropertyDeclaration,
            injectMethodBuilder: FunSpec.Builder,
            parentClassName: ClassName
        ) {
            val annotation = ksPropertyDeclaration.findAnnotationWithType<Autowired>()!!
            val fieldName = ksPropertyDeclaration.simpleName.asString()
            val propertyType = ksPropertyDeclaration.getKotlinPoetTTypeGeneric()
            if (annotation.name.isEmpty()) { // User has not set service path, then use byType.
                injectMethodBuilder.addStatement(
                    "substitute.$fieldName = %T.getInstance().navigation(%T::class.java)",
                    AROUTER_CLASS_NAME, propertyType
                )
            } else { // use byName
                injectMethodBuilder.addStatement(
                    "substitute.$fieldName = %T.getInstance().build(%S).navigation() as %T",
                    AROUTER_CLASS_NAME, annotation.name, propertyType
                )
            }
            // Validator
            if (annotation.required) {
                val message =
                    "The field '${fieldName}' is null, in class '${parentClassName.simpleName}' !"
                injectMethodBuilder.beginControlFlow("if (substitute.$fieldName == null)")
                    .addStatement(
                        "throw RuntimeException(%S)",
                        message
                    )
                    .endControlFlow()
            }
        }

        /**
         * Inject field for activity and fragment
         * */
        private fun addActivityOrFragmentStatement(
            property: KSPropertyDeclaration,
            method: FunSpec.Builder,
            type: TypeKind,
            parentRouteType: RouteType,
            parentClassName: ClassName
        ) {
            val fieldName = property.simpleName.asString()
            val isNullable = property.type.resolve().isMarkedNullable
            val isActivity = when (parentRouteType) {
                RouteType.ACTIVITY -> true
                RouteType.FRAGMENT -> false
                else -> {
                    throw IllegalAccessException("The field [$fieldName] need autowired from intent, its parent must be activity or fragment!")
                }
            }
            val intent = if (isActivity) "intent?.extras" else "arguments"
            val annotation = property.findAnnotationWithType<Autowired>()!!
            val bundleName = annotation.name.ifEmpty { fieldName }

            val getPrimitiveTypeMethod: String = when (type) {
                TypeKind.BOOLEAN -> "getBoolean"
                TypeKind.BYTE -> "getByte"
                TypeKind.SHORT -> "getShort"
                TypeKind.INT -> "getInt"
                TypeKind.LONG -> "getLong"
                TypeKind.CHAR -> "getChar"
                TypeKind.FLOAT -> "getFloat"
                TypeKind.DOUBLE -> "getDouble"
                else -> ""
            }
            if (getPrimitiveTypeMethod.isNotEmpty()) {
                val primitiveCodeBlock = if (isNullable) {
                    CodeBlock.builder()
                        .beginControlFlow("substitute.${intent}?.let")
                        .beginControlFlow("if(it.containsKey(%S))", bundleName)
                        .addStatement(
                            "substitute.%L = it.${getPrimitiveTypeMethod}(%S)",
                            fieldName,
                            bundleName
                        )
                        .endControlFlow()
                        .endControlFlow()
                        .build()
                } else {
                    CodeBlock.builder()
                        .beginControlFlow("substitute.${intent}?.let")
                        .addStatement(
                            "substitute.%L = it.${getPrimitiveTypeMethod}(%S, substitute.%L)",
                            fieldName,
                            bundleName,
                            fieldName
                        )
                        .endControlFlow()
                        .build()
                }
                method.addCode(primitiveCodeBlock)
            } else {
                // such as: val param = List<JailedBird> ==> %T ==> List<JailedBird>
                val parameterClassName = property.getKotlinPoetTTypeGeneric()

                when (type) {
                    TypeKind.STRING -> {
                        method.addCode(
                            CodeBlock.builder()
                                .beginControlFlow("substitute.${intent}?.let")
                                .addStatement(
                                    "substitute.%L = it.getString(%S, substitute.%L)",
                                    fieldName,
                                    bundleName,
                                    fieldName
                                )
                                .endControlFlow()
                                .build()
                        )
                    }
                    TypeKind.SERIALIZABLE -> {
                        val beginStatement = if (isActivity) {
                            "(substitute.intent?.getSerializableExtra(%S) as? %T)?.let"
                        } else {
                            "(substitute.arguments?.getSerializable(%S) as? %T)?.let"
                        }
                        method.addCode(
                            CodeBlock.builder()
                                .beginControlFlow(beginStatement, bundleName, parameterClassName)
                                .addStatement("substitute.%L = it", fieldName)
                                .endControlFlow().build()
                        )
                    }
                    TypeKind.PARCELABLE -> {
                        val beginStatement = if (isActivity) {
                            "substitute.intent?.getParcelableExtra<%T>(%S)?.let"
                        } else {
                            "substitute.arguments?.getParcelable<%T>(%S)?.let"
                        }
                        method.addCode(
                            CodeBlock.builder()
                                .beginControlFlow(beginStatement, parameterClassName, bundleName)
                                .addStatement("substitute.%L = it", fieldName)
                                .endControlFlow().build()
                        )
                    }
                    TypeKind.OBJECT -> {
                        val message =
                            "You want automatic inject the field '${fieldName}' in class '${parentClassName.simpleName}', then you should implement 'SerializationService' to support object auto inject!"
                        method.beginControlFlow("if(serializationService != null)")
                            .addStatement(
                                "val res = substitute.${intent}?.getString(%S)",
                                bundleName
                            )
                            .beginControlFlow("if(!res.isNullOrEmpty())")
                            .addStatement(
                                "serializationService?.parseObject<%T>(res, (object : TypeWrapper<%T>(){}).type)?.let{ substitute.%L = it }",
                                parameterClassName, parameterClassName, bundleName
                            )
                            .endControlFlow()
                            .nextControlFlow("else")
                            // Kotlin-poet Notice: Long lists line wrapping makes code not compile
                            // https://github.com/square/kotlinpoet/issues/1346 , temp using """  """ to wrap long string (perhaps can optimize it)
                            // Fix: use val message = "long string" to wrapper long string, and use %S for message, it will not be break
                            .addStatement(
                                "Log.e(%S , %S)", Consts.TAG, message
                            )
                            .endControlFlow()
                    }
                    else -> {
                        // This branch will not be reach
                        error("This branch will not be reach")
                    }
                }
                // Validator, Primitive type wont be check.
                if (annotation.required) {
                    method.beginControlFlow("if (substitute.$fieldName == null)")
                        .addStatement(
                            "Log.e(\"${Consts.TAG}\" , \"\"\"The field '%L' in class '%L' is null!\"\"\")",
                            fieldName, parentClassName.simpleName
                        )
                    method.endControlFlow()
                }
            }

        }
    }

}

