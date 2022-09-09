package kamon.instrumentation.cats3

import kamon.Kamon
import kamon.context.{Context, Storage}
import kamon.context.Storage.Scope
import kamon.instrumentation.context.HasContext
import kanela.agent.api.instrumentation.InstrumentationBuilder
import kanela.agent.libs.net.bytebuddy.asm.Advice


class IOFiberInstrumentation extends InstrumentationBuilder {

  onType("cats.effect.IOFiber")
    .mixin(classOf[HasContext.Mixin])
    .advise(isConstructor.and(takesArguments(9)), AfterFiberInit)
    .advise(method("suspend"), SaveCurrentContextOnExit)
    .advise(method("resume"), RestoreContextOnSuccessfulResume)
    .advise(method("run"), RunLoopWithContext)


  // We must save the context on calls to `schedule*`/`reschedule*` because the Fiber
  // might start executing on another thread before the call to `run` on the current
  // thread terminates.
  onTypes("cats.effect.IOFiber")
    .advise(anyMethods(
      "rescheduleFiber",
      "scheduleFiber",
      "scheduleOnForeignEC",
    ), SetContextOnNewFiber)

  onTypes("cats.effect.unsafe.WorkStealingThreadPool")
    .advise(anyMethods(
      "scheduleFiber",
      "rescheduleFiber",
      "scheduleExternal"
    ), SetContextOnNewFiberForWSTP)


  // Scheduled actions like `IO.sleep` end up calling `resume` from the scheduler thread,
  // which always leaves a dirty thread. This wrapper ensures that scheduled actions are
  // executed with the same Context that was available when they were scheduled, and then
  // reset the scheduler thread to the empty context.
  onSubTypesOf("cats.effect.unsafe.Scheduler")
    .advise(method("sleep"), classOf[CleanSchedulerContextAdvice])
}

object AfterFiberInit {

  @Advice.OnMethodExit
  def exit(@Advice.This fiber: HasContext): Unit ={
    fiber.setContext(Kamon.currentContext())
  }
}

object RunLoopWithContext {

  @Advice.OnMethodEnter()
  def enter(@Advice.This fiber: Any): Scope = {
    val ctxFiber = fiber.asInstanceOf[HasContext].context
    Kamon.storeContext(ctxFiber)
  }

  @Advice.OnMethodExit()
  def exit(@Advice.Enter scope: Scope, @Advice.This fiber: Any): Unit = {
    val leftContext = Kamon.currentContext()
    scope.close()

    fiber.asInstanceOf[HasContext].setContext(leftContext)
  }
}

object RestoreContextOnSuccessfulResume {

  @Advice.OnMethodExit()
  def exit(@Advice.This fiber: Any, @Advice.Return wasSuspended: Boolean): Unit = {
    if(wasSuspended) {
      val ctxFiber = fiber.asInstanceOf[HasContext].context
      Kamon.storeContext(ctxFiber)
    }
  }
}

object SaveCurrentContextOnExit {
  @Advice.OnMethodExit()
  def exit(@Advice.This fiber: Any): Unit = {
    fiber.asInstanceOf[HasContext].setContext(Kamon.currentContext())
  }
}


object SetContextOnNewFiber {

  @Advice.OnMethodEnter()
  def enter(@Advice.Argument(1) fiber: Any): Unit =
    fiber.asInstanceOf[HasContext].setContext(Kamon.currentContext())
}

object SetContextOnNewFiberForWSTP {

  @Advice.OnMethodEnter()
  def enter(@Advice.Argument(0) fiber: Any): Unit = {
    fiber.asInstanceOf[HasContext].setContext(Kamon.currentContext())
  }
}