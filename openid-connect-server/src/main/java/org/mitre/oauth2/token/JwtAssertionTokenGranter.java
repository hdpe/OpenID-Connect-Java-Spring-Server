/**
 * 
 */
package org.mitre.oauth2.token;

import java.security.NoSuchAlgorithmException;
import java.text.ParseException;
import java.util.Date;

import org.mitre.jwt.signer.service.JwtSigningAndValidationService;
import org.mitre.oauth2.model.ClientDetailsEntity;
import org.mitre.oauth2.model.OAuth2AccessTokenEntity;
import org.mitre.oauth2.service.ClientDetailsEntityService;
import org.mitre.oauth2.service.OAuth2TokenEntityService;
import org.mitre.openid.connect.config.ConfigurationPropertiesBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.oauth2.common.OAuth2AccessToken;
import org.springframework.security.oauth2.common.exceptions.InvalidClientException;
import org.springframework.security.oauth2.provider.AuthorizationRequest;
import org.springframework.security.oauth2.provider.token.AbstractTokenGranter;
import org.springframework.stereotype.Component;

import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jwt.JWT;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.JWTParser;
import com.nimbusds.jwt.SignedJWT;

/**
 * @author jricher
 *
 */
@Component("jwtAssertionTokenGranter")
public class JwtAssertionTokenGranter extends AbstractTokenGranter {

	private static final String grantType = "urn:ietf:params:oauth:grant-type:jwt-bearer";

	// keep down-cast versions so we can get to the right queries
	private OAuth2TokenEntityService tokenServices;
	private ClientDetailsEntityService clientDetailsService;
	
	@Autowired
	private JwtSigningAndValidationService jwtService;
	
	@Autowired
	private ConfigurationPropertiesBean config;
	
	@Autowired
	public JwtAssertionTokenGranter(OAuth2TokenEntityService tokenServices, ClientDetailsEntityService clientDetailsService) {
	    super(tokenServices, clientDetailsService, grantType);
	    this.tokenServices = tokenServices;
	    this.clientDetailsService = clientDetailsService;
    }

	/* (non-Javadoc)
     * @see org.springframework.security.oauth2.provider.token.AbstractTokenGranter#getOAuth2Authentication(org.springframework.security.oauth2.provider.AuthorizationRequest)
     */
    @Override
    protected OAuth2AccessToken getAccessToken(AuthorizationRequest authorizationRequest) {
    	// read and load up the existing token
	    String incomingTokenValue = authorizationRequest.getAuthorizationParameters().get("assertion");
	    OAuth2AccessTokenEntity incomingToken = tokenServices.readAccessToken(incomingTokenValue);
	    
	    ClientDetailsEntity client = incomingToken.getClient();

	    
	    if (incomingToken.getScope().contains(OAuth2AccessTokenEntity.ID_TOKEN_SCOPE)) {
		    
	    	if (!client.getClientId().equals(authorizationRequest.getClientId())) {
		    	throw new InvalidClientException("Not the right client for this token");
		    }

		    // it's an ID token, process it accordingly
	    	
	    	// TODO: make this use the idtoken class
	    	JWT idToken;
            try {
	            idToken = JWTParser.parse(incomingTokenValue);
            } catch (ParseException e1) {
	            // TODO Auto-generated catch block
	            e1.printStackTrace();
	            return null;
            }
	    	
	    	OAuth2AccessTokenEntity accessToken = tokenServices.getAccessTokenForIdToken(incomingToken);
	    	
	    	if (accessToken != null) {
	    	
	    		//OAuth2AccessTokenEntity newIdToken = tokenServices.get
	    		
	    		OAuth2AccessTokenEntity newIdTokenEntity = new OAuth2AccessTokenEntity();
	    		
	    		// FIXME: we shouldn't have to roundtrip this through JSON to get it to copy all existing claims
	    		JWTClaimsSet claims;
                try {
	                claims = JWTClaimsSet.parse(idToken.getJWTClaimsSet().toJSONObject());
                } catch (ParseException e1) {
	                // TODO Auto-generated catch block
	                e1.printStackTrace();
	                return null;
                }

	    		// update expiration and issued-at claims
				if (client.getIdTokenValiditySeconds() != null) {
					Date expiration = new Date(System.currentTimeMillis() + (client.getIdTokenValiditySeconds() * 1000L));
					// FIXME: Nimbus-JOSE Date fields
					claims.setExpirationTimeClaim(expiration.getTime());
					newIdTokenEntity.setExpiration(expiration);
				}
				// FIXME: Nimbus-JOSE Date fields
				claims.setIssuedAtClaim(new Date().getTime());

				
				SignedJWT newIdToken = new SignedJWT((JWSHeader) idToken.getHeader(), claims);
				try {
	                jwtService.signJwt(newIdToken);
                } catch (NoSuchAlgorithmException e) {
	                // TODO Auto-generated catch block
	                e.printStackTrace();
                }
				
				newIdTokenEntity.setJwt(newIdToken);
				newIdTokenEntity.setAuthenticationHolder(incomingToken.getAuthenticationHolder());
				newIdTokenEntity.setScope(incomingToken.getScope());
				newIdTokenEntity.setClient(incomingToken.getClient());
				
				newIdTokenEntity = tokenServices.saveAccessToken(newIdTokenEntity);

				// attach the ID token to the access token entity
				accessToken.setIdToken(newIdTokenEntity);
				accessToken = tokenServices.saveAccessToken(accessToken);
				
				// delete the old ID token
				tokenServices.revokeAccessToken(incomingToken);
				
				return newIdTokenEntity;
	    		
	    	}
	    	
	    }
	    
	    return null;

	    /*
	     * Otherwise, process it like an access token assertion ... which we don't support yet so this is all commented out
	     * /
	    if (jwtService.validateSignature(incomingTokenValue)) {

	    	Jwt jwt = Jwt.parse(incomingTokenValue);
	    	
	    	
	    	if (oldToken.getScope().contains("id-token")) {
	    		// TODO: things
	    	}
	    	
	    	// TODO: should any of these throw an exception instead of returning null?
	    	JwtClaims claims = jwt.getClaims();
	    	if (!config.getIssuer().equals(claims.getIssuer())) {
	    		// issuer isn't us
	    		return null;
	    	}
	    	
	    	if (!authorizationRequest.getClientId().equals(claims.getAudience())) {
	    		// audience isn't the client
	    		return null;
	    	}
	    	
	    	Date now = new Date();
	    	if (!now.after(claims.getExpiration())) {
	    		// token is expired
	    		return null;
	    	}

	    	// FIXME
	    	// This doesn't work. We need to look up the old token, figure out its scopes and bind it appropriately.
	    	// In the case of an ID token, we need to look up its parent access token and change the reference, and revoke the old one, and
	    	// that's tricky.
	    	// we might need new calls on the token services layer to handle this, and we might
	    	// need to handle id tokens separately.
	    	return new OAuth2Authentication(authorizationRequest, null);
	    	
	    } else {
	    	return null; // throw error??
	    }
	    */
	    
    }

	
	
}